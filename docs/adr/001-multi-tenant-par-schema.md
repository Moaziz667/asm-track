# ADR-001 — Isolation des clients par schéma PostgreSQL

**Statut :** acceptée · **Portée :** AppBackend, DeliveryMicroservice, DriverService

---

## Contexte

ASM Track est vendu à plusieurs transporteurs. Chacun voit ses livraisons, ses livreurs, ses
tournées — et rien d'autre. Cette frontière n'est pas un confort d'affichage : deux transporteurs
d'une même ville sont concurrents, et une fuite de leurs carnets de livraison serait un incident
commercial avant d'être un incident technique.

La contrainte tient donc en une phrase : **l'isolation doit tenir même quand une requête est mal
écrite.** Un filtre oublié dans un `WHERE` ne doit pas suffire à faire traverser la frontière.

---

## Décision

Un **schéma PostgreSQL par client**, nommé `company_<32 hexadécimaux>` — l'UUID de l'entreprise, sans
tirets, pour former un identifiant SQL valide sans guillemets.

La résolution passe par le SPI de multi-tenance d'Hibernate :

| Composant | Rôle |
|---|---|
| `TenantContextFilter` | lit l'entreprise dans le JWT et la pose dans le contexte de la requête |
| `TenantIdentifierResolver` | expose ce contexte à Hibernate |
| `SchemaMultiTenantConnectionProvider` | exécute `SET search_path` au retrait de connexion |
| `TenantSchema` | dérive le nom de schéma — la seule source de vérité |
| `TenantMigrationRunner` | rejoue les migrations Flyway sur chaque schéma au démarrage |

**Un seul pool de connexions**, partagé. Le schéma est choisi au retrait, puis remis à `public` au
retour. Pas de pool par client, donc pas d'explosion du nombre de connexions quand la clientèle
grandit.

### Le point de sécurité

Le nom de schéma entre dans une requête SQL par concaténation :

```java
stmt.execute("SET search_path TO \"" + schema + "\", public");
```

C'est acceptable ici, et seulement parce que l'entrée n'est jamais une chaîne libre :

```java
public static String schemaFor(UUID companyId) {
    return "company_" + companyId.toString().replace("-", "");
}
```

L'argument est un `UUID` **déjà parsé**. Son alphabet de sortie se limite à `[0-9a-f]`, donc aucune
chaîne injectable ne peut atteindre cette ligne. La sûreté vient du type, pas d'un échappement — ce
qui la rend vérifiable en lisant la signature.

---

## Alternatives écartées

### Une base de données par client
La meilleure isolation possible, et la plus coûteuse : autant de pools, de connexions et de
migrations à orchestrer que de clients. Sur une plateforme visant des PME de transport, cela rend
l'onboarding d'un client une opération d'infrastructure. Écartée pour le coût opérationnel, pas pour
un défaut technique.

### Une colonne `company_id` sur chaque table
La plus simple, et c'est son piège : **elle repose sur la discipline.** Chaque requête doit filtrer,
et il suffit d'un `findAll()` pour qu'un client voie les données d'un autre. Le défaut est silencieux
— la requête réussit, les tests passent, l'incident se découvre en production. La frontière doit être
plus difficile à franchir par accident que par intention.

### Un schéma par client, résolu à la main
Écrire `SET search_path` dans chaque service, sans passer par Hibernate. Cela déplace la même
question dans le code métier, où on l'oublie.

---

## Conséquences

**Ce qu'on gagne.** L'isolation est portée par le moteur de base de données, pas par le code
applicatif. Une requête sans filtre ne peut pas sortir du schéma courant. La sauvegarde et la
restauration d'un client isolé deviennent des opérations naturelles (`pg_dump -n`).

**Ce que ça coûte.** Les migrations doivent être rejouées sur chaque schéma — d'où
`TenantMigrationRunner`.

**Où vit ce code.** Les huit classes ci-dessus sont dans un module partagé, `asm-tenant-core`,
consommé par les quatre services via un *composite build* Gradle. Elles ont d'abord existé en trois
ou quatre copies, et avaient déjà divergé : `TenantContextFilter` comptait quatre variantes, et une
capacité ajoutée à une seule copie — lister les schémas provisionnés, pour les services sans base —
était invisible aux trois autres.

Ce que ces copies encodaient réellement, c'était **une différence de configuration** : quels chemins
peuvent arriver sans tenant. Elle est désormais déclarée là où elle appartient :

```yaml
asm:
  tenant:
    tenant-less-prefixes: /api/v1/public/,/api/v1/auth/,/api/v1/dev/
```

Le module expose deux configurations séparées, et cette séparation a une raison précise :
`TenantCoreConfig` (HTTP + AMQP) est prise par les quatre services, `TenantJpaConfig` seulement par
les trois qui ont des schémas. L'adaptateur ERP propage un locataire pour résoudre les bons
identifiants ERP, mais garde son propre petit magasin H2 sans aucun schéma — un module monolithique
lui aurait imposé la multi-tenance Hibernate et serait resté inadoptable chez lui.

**Le coût de cette mise en commun.** Le contexte de build Docker de chaque service est passé du
dossier du service à `Microservices/`, puisqu'un contexte par service ne peut pas voir un module
frère. D'où un `.dockerignore` à ce niveau, sans quoi chaque image embarquerait les fichiers de
travail de tous les autres services.

**Ce qui reste ouvert.** La création d'un client provisionne un schéma ; sa suppression n'est pas
automatisée.
