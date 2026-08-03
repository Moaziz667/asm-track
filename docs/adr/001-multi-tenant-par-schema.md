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
`TenantMigrationRunner`. Et l'infrastructure de résolution (8 classes) est aujourd'hui **dupliquée
dans trois services**, où elle a commencé à diverger : `TenantContextFilter` existe en quatre
exemplaires et quatre variantes. C'est une dette identifiée, dont la résolution — un module partagé,
sur le modèle de `asm-canonical-model` — est planifiée et non faite.

**Ce qui reste ouvert.** La création d'un client provisionne un schéma ; sa suppression n'est pas
automatisée.
