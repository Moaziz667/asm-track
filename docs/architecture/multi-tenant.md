# Multi-tenant

> Décision et alternatives écartées : [ADR-001](../adr/001-multi-tenant-par-schema.md).
> Cette page explique **comment ça marche**.

---

## Un schéma PostgreSQL par client

```mermaid
erDiagram
    BASE_DELIVERY_DB {
        string public "flyway_schema_history, tables partagées"
    }
    SCHEMA_A["company_54ed4906…"] {
        string deliveries "les livraisons du client A"
        string orders "ses commandes"
        string routes "ses tournées"
    }
    SCHEMA_B["company_3b2ae6fe…"] {
        string deliveries "les livraisons du client B"
        string orders "ses commandes"
        string routes "ses tournées"
    }
    BASE_DELIVERY_DB ||--o{ SCHEMA_A : contient
    BASE_DELIVERY_DB ||--o{ SCHEMA_B : contient
```

Le nom du schéma est dérivé de l'UUID de l'entreprise, tirets retirés :

```java
public static String schemaFor(UUID companyId) {
    return "company_" + companyId.toString().replace("-", "");
}
```

!!! success "Sûreté par construction"
    L'argument est un `UUID` **déjà parsé**. Son alphabet de sortie se limite donc à `[0-9a-f]`, et
    aucune chaîne injectable ne peut atteindre le `SET search_path` qui concatène ce nom. La sûreté
    vient du **type**, pas d'un échappement — elle se vérifie en lisant une signature.

---

## Comment le schéma est choisi à l'exécution

```mermaid
sequenceDiagram
    autonumber
    participant F as TenantContextFilter
    participant TC as TenantContext<br/>(ThreadLocal)
    participant R as TenantIdentifierResolver
    participant P as SchemaMultiTenantConnectionProvider
    participant Pool as Pool Hikari (unique)
    participant DB as PostgreSQL

    F->>TC: set(companyId)
    Note over F,TC: depuis l'en-tête X-Company-Id
    F->>R: (la requête s'exécute)
    R->>TC: get()
    TC-->>R: companyId
    R-->>P: « company_54ed4906… »
    P->>Pool: emprunte une connexion
    Pool-->>P: connexion
    P->>DB: SET search_path TO "company_54ed4906…", public
    DB-->>P: prêt
    Note over P,DB: toutes les requêtes JPA voient<br/>uniquement ce schéma
    P->>DB: SET search_path TO public
    P->>Pool: rend la connexion
    Note over Pool: aucun état de locataire ne fuit<br/>vers l'emprunteur suivant
```

**Pourquoi ce diagramme.** Il montre le point que tout le monde manque : le `search_path` est posé
sur **la connexion que Hibernate va utiliser**, puis remis à zéro au retour. C'est ce qui permet
d'avoir **un seul pool** partagé par tous les clients.

**Observations importantes.**

- **Un seul pool**, pas un pool par client. Sans cela, cent clients signifieraient cent pools et une
  explosion du nombre de connexions PostgreSQL.
- La remise à `public` au retour n'est pas cosmétique : sans elle, la requête suivante hériterait du
  schéma du client précédent.
- Le mécanisme est celui d'Hibernate (`MultiTenantConnectionProvider`), pas une invention maison.

!!! danger "Erreur classique"
    Poser le `search_path` dans un filtre servlet, sur une connexion empruntée pour l'occasion. Ce
    n'est **pas** la connexion que le transaction manager utilisera ensuite. C'est précisément la
    version qui ne fonctionnait pas et que ce provider a remplacée.

---

## Le module partagé

Les huit classes de cette mécanique vivent dans **`asm-tenant-core`**, consommé par les quatre
services via un *composite build* Gradle.

```mermaid
flowchart TB
    subgraph M["asm-tenant-core"]
        direction TB
        TC["TenantContext"]
        TS["TenantSchema"]
        F["web/TenantContextFilter"]
        A1["amqp/TenantMessagePostProcessor"]
        A2["amqp/TenantInboundPostProcessor"]
        J1["jpa/TenantIdentifierResolver"]
        J2["jpa/SchemaMultiTenantConnectionProvider"]
        J3["jpa/TenantIterator"]
        CC["TenantCoreConfig<br/>(web + AMQP)"]
        JC["TenantJpaConfig<br/>(Hibernate)"]
    end

    AB["AppBackend"] --> CC & JC
    DEL["DeliveryMicroservice"] --> CC & JC
    DRV["DriverService"] --> CC & JC
    ERP["ErpAdapterService"] --> CC

    style M fill:#e8eaf6,stroke:#3f51b5
    style ERP fill:#fff3e0,stroke:#ef6c00
```

**Pourquoi deux configurations séparées.** L'`ErpAdapterService` propage un locataire par HTTP et
AMQP — pour choisir les bons identifiants ERP — mais garde son propre magasin H2 **sans aucun schéma
par client**. Un module monolithique lui aurait imposé la multi-tenance Hibernate et serait resté
inadoptable chez lui.

!!! note "Ce que la duplication cachait"
    Ces classes ont d'abord existé en trois ou quatre copies. Elles avaient déjà divergé :
    `TenantContextFilter` comptait quatre variantes. Pire, une capacité ajoutée à **une seule** copie
    — lister les schémas provisionnés — restait invisible aux trois autres. La duplication n'empêche
    pas seulement les correctifs de se propager : les fonctionnalités non plus.

---

## Provisionner et faire évoluer un client

```mermaid
flowchart TD
    subgraph N["Nouveau client"]
        A["TenantOnboardingService"] -->|"POST /internal/tenants/{id}/provision"| B["AppBackend"]
        A --> C["DeliveryMicroservice"]
        A --> D["DriverService"]
        B & C & D --> E["CREATE SCHEMA IF NOT EXISTS<br/>+ flyway.migrate()"]
    end

    subgraph S["À chaque démarrage"]
        F["TenantMigrationRunner"] --> G["TenantIterator<br/>lit les schémas company_*"]
        G --> H["provision() sur chacun<br/>= migrations en attente"]
    end

    style N fill:#e3f2fd,stroke:#1565c0
    style S fill:#e8f5e9,stroke:#2e7d32
```

**Les deux moments sont nécessaires, et c'est le second qu'on oublie.**

Sans `TenantMigrationRunner`, une nouvelle migration atteindrait le schéma `public` et les clients
créés **après**, jamais ceux déjà en base. Ils garderaient le schéma de leur naissance pendant que
les entités JPA attendraient les nouvelles colonnes.

!!! warning "C'est exactement le défaut qu'avait `AppBackend`"
    Il exécutait un `schema.sql` sans suivi de version. Modifier le fichier ne touchait que les
    nouveaux clients. Le passage à Flyway **seul** n'aurait rien corrigé — il fallait aussi le runner.

**La source de vérité des locataires est le catalogue des schémas**, pas une table `companies` :

```sql
SELECT schema_name FROM information_schema.schemata
WHERE schema_name LIKE 'company\_%'
```

C'est la vérité opérationnelle : exactement les clients qui ont un schéma à traiter. Une entreprise
créée dans Keycloak mais pas encore provisionnée n'a pas de schéma, donc rien à parcourir — et aucun
risque qu'un traitement échoue sur des tables inexistantes.

---

## Et le locataire dans les messages ?

```mermaid
sequenceDiagram
    participant A as Service émetteur
    participant PP as TenantMessagePostProcessor
    participant MQ as RabbitMQ
    participant IP as TenantInboundPostProcessor
    participant B as Service consommateur

    A->>PP: publie un événement
    PP->>PP: lit TenantContext
    PP->>MQ: message + en-tête X-Company-Id
    MQ->>IP: livraison au consommateur
    IP->>IP: clear() PUIS set(companyId)
    IP->>B: le listener s'exécute
    Note over B: son travail en base atteint<br/>le bon schéma
```

**Le `clear()` avant le `set()` est la partie importante.** Un message arrivant **sans** en-tête ne
doit jamais hériter du locataire du message précédent traité sur le même thread. Il retombe sur le
schéma par défaut — jamais sur celui d'un autre client. Un en-tête manquant est ainsi *fail-safe*,
pas une fuite.
