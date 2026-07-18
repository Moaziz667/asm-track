# ASM Track — Plan Multi-Tenant (schéma-par-company)

> Décision d'isolation : **schéma-par-tenant seul** (`company_{uuid}` + `search_path`).
> Une seule instance Postgres partagée, un schéma par company, une auth Keycloak partagée
> (company = Organization). Le JWT porte le tenant ; l'URL n'est que du sucre.
>
> Ce document = **diagnostic de l'existant `feature/multi-tenant` + plan correctif**. L'ossature est
> posée mais l'isolation ne fonctionne pas dans l'état actuel. On corrige, on supprime le doublon,
> on fiabilise via Hibernate (pas un servlet filter).

---

## 0. TL;DR

| Sujet | État réel | Action |
|---|---|---|
| `SET search_path` via `TenantSchemaFilter` | 🔴 **inopérant** (connexion fermée avant les requêtes) | **Remplacer** par Hibernate `MultiTenantConnectionProvider` |
| V31 `company_id NOT NULL` sur ~25 tables | 🔴 **contradictoire** avec schéma-par-tenant | **Supprimer** V31 + jamais de colonne company_id |
| `TenantRoutingDataSource` | 🔴 **dead code** (jamais câblé) | **Supprimer** dans les 4 services |
| Fallback tenant par défaut (Gateway + filter) | 🔴 **fuite cross-tenant silencieuse** | **Fail-closed** (401/403) hors bootstrap |
| `TenantContext` (ThreadLocal) | 🟢 correct | Garder, réutiliser tel quel |
| `TenantSchemaProvisioner` (CREATE SCHEMA + Flyway) | 🟡 bon principe, à durcir | Garder, ajouter rollback + garde nom |
| `TenantProvisioningController` (`/internal/tenants`) | 🟡 OK mais exposé | Garder, verrouiller (réseau interne + rôle SERVICE) |
| Extraction claim `org_id`/`organization` au Gateway | 🟢 correct | Garder, retirer le fallback |
| Flyway app au démarrage (`public`) | 🟡 migre `public` | Désactiver l'auto-migrate `public`, migrer par schéma |

---

## 1. Diagnostic détaillé de l'existant

### 1.1 🔴 Le `SET search_path` ne s'applique jamais aux requêtes
`TenantSchemaFilter.doFilter` :
```java
try (Connection conn = dataSource.getConnection();     // connexion A
     Statement stmt = conn.createStatement()) {
    stmt.execute("SET search_path TO \"company_x\", public");
}                                                       // <-- conn A rendue au pool ICI
chain.doFilter(request, response);                      // JPA prend connexion B => search_path = public
```
`search_path` est un état **de session** (par connexion). Avec `open-in-view: false`, chaque transaction
prend une connexion fraîche du pool → jamais la connexion A. **Résultat : toutes les requêtes lisent/écrivent
le même schéma.** L'isolation est une illusion. C'est le défaut le plus grave.

> Un servlet filter ne peut pas fixer ça de façon fiable : il n'a aucune prise sur *quelle* connexion
> Hibernate va emprunter par la suite. Le point d'accroche correct est **au moment où Hibernate demande
> une connexion**, pas dans la couche web.

### 1.2 🔴 Deux modèles d'isolation mélangés
- Schéma-par-tenant : `TenantSchemaProvisioner`, `TenantSchemaFilter`, `company_{uuid}`.
- Row-level : `V31__add_company_id_to_all_tables.sql` ajoute `company_id UUID NOT NULL` + index + backfill
  sur ~25 tables.

En schéma-par-tenant, **le schéma EST la frontière** → `company_id` est redondant. Pire :
- `NOT NULL` cassera tout `INSERT` (aucune entité JPA ne mappe/remplit `company_id`).
- Le provisioner rejoue Flyway (donc V31) dans **chaque** schéma → colonne morte dupliquée partout.
- Backfill `...0001` = logique row-level qui n'a aucun sens ici.

**→ V31 supprimée. Aucune colonne `company_id` dans les tables métier.** (Le lien user↔company vit dans le
control-plane / Keycloak, pas dans chaque table.)

### 1.3 🔴 `TenantRoutingDataSource` = dead code
`AbstractRoutingDataSource` sans `setTargetDataSources(...)`, jamais déclaré `@Bean`/`@Primary`. Présent dans
Delivery, Driver, AppBackend. Vestige de la 1ʳᵉ approche (datasource-par-tenant = le piège des pools N×services).
On a tranché pour **1 pool + search_path** → **supprimer les 3 fichiers** (+ Driver/AppBackend).

### 1.4 🔴 Fallback silencieux = anti-isolation
- `UserContextHeaderFilter` (Gateway) : `org_id` absent → `X-Company-Id = ...0001`.
- `TenantContextFilter` : header absent/invalide → `...0001`.

Pour de l'isolation « dure », il faut **fail-closed** : pas de tenant résoluble = **401/403**, jamais un
tenant fourre-tout. Sinon une mauvaise config Keycloak = données d'un client visibles chez « default », en
silence. (Exception maîtrisée : endpoints de bootstrap/provisioning et jobs système — voir §4.)

### 1.5 🟡 Ce qui est bon et qu'on garde
- `TenantContext` (ThreadLocal<UUID>) : propre.
- `TenantContextFilter` : bon principe (lire header → ThreadLocal), à durcir (fail-closed).
- Extraction claim `org_id` puis `organization[].id` au Gateway : correcte.
- `TenantSchemaProvisioner` : CREATE SCHEMA + Flyway ciblé = la bonne mécanique de provisioning.
- Le sanitizing anti-spoof des headers `X-*` au Gateway (déjà présent) : indispensable, garder.

---

## 2. Architecture cible

### 2.1 Propagation du tenant (inchangée dans le principe)
```
JWT (claim org_id = companyId, posé par KC Organizations)
  → Gateway UserContextHeaderFilter : valide, réinjecte X-Company-Id (strip anti-spoof déjà en place)
  → Service TenantContextFilter : X-Company-Id → TenantContext.set(uuid)   [fail-closed]
  → Hibernate CurrentTenantIdentifierResolver : lit TenantContext
  → Hibernate MultiTenantConnectionProvider : SET search_path sur LA connexion empruntée, reset au release
  → Requêtes exécutées dans company_{uuid}
```

### 2.2 Le vrai fix : multi-tenancy Hibernate (SCHEMA)
Remplace `TenantSchemaFilter` par le mécanisme natif Hibernate 6 (Spring Boot 3) :

- **`CurrentTenantIdentifierResolver`** → renvoie `TenantContext.get()` (ou le schéma par défaut au boot).
- **`MultiTenantConnectionProvider`** → sur `getConnection(tenantId)` : emprunte au pool Hikari **unique**,
  exécute `SET search_path TO "company_{id}", public` ; sur `releaseConnection` : `SET search_path TO public`
  (reset avant retour au pool — évite la fuite d'état entre requêtes).
- Config JPA : `hibernate.multiTenancy=SCHEMA`, brancher les deux beans.

C'est **1 seul pool** par service (pas de N datasources), et le search_path est garanti posé sur la
connexion effectivement utilisée par chaque transaction. C'est le remplacement direct et fiable de 1.1.

### 2.3 Provisioning
- `TenantSchemaProvisioner.provision(companyId)` : garder. Ajouter :
  - garde sur le nom (`company_` + UUID canonique only ; refuser toute autre entrée → anti-injection).
  - rollback : si `flyway.migrate()` échoue, `DROP SCHEMA ... CASCADE` (pas de schéma à moitié migré).
- `/internal/tenants/{id}/provision` : garder mais **verrouiller** — route interne (non exposée par le
  Gateway public), auth service-to-service (rôle `SERVICE`), idempotent (`CREATE SCHEMA IF NOT EXISTS`).

### 2.4 Flyway
- Désactiver l'auto-migrate du schéma `public` au démarrage (`spring.flyway.enabled=false`) **ou** le cantonner
  à un schéma technique `_admin` (control-plane) — les tables métier ne vivent QUE dans les schémas tenant.
- Migration des tenants = **uniquement** via le provisioner (au provisioning) + un job de « migrate-all » au
  déploiement qui **boucle sur les companies connues** et rejoue Flyway par schéma.
- ⚠️ **Supprimer V31** avant tout run (sinon elle s'exécute dans chaque schéma).

### 2.5 Control-plane (annuaire) — **Keycloak Organizations** (décidé 2026-07-18)
**Le registre des tenants = KC Organizations. PAS de table `companies` partagée.**
- `companyId = org_id KC` → l'org KC est déjà l'annuaire. Le mapper `organization` (avec `addOrganizationId=true`)
  pousse l'id dans le token ; le scope `organization` est **default** sur admin-web.
- Énumérer les tenants (onboarding, jobs, migrate-all) = `GET {realm}/organizations` via un client admin KC,
  **pas** un `SELECT FROM public.companies`.
- La branding-row de chaque tenant (nom, logo, couleur) vit dans **son** schéma (`company_x.companies`), 1 ligne.
  L'ERP config vit dans **son** `system_settings`. Rien de cross-tenant en DØB.
- Une table de control-plane partagée ne sera ajoutée QUE si ASM a besoin d'une métadonnée cross-tenant que KC
  ne porte pas (ex: statut de provisioning des 3 schémas, billing) — **pas avant**.

> ⚠️ Piège : `TenantIterator` fait aujourd'hui `SELECT id FROM companies` sur `public` (vidé par le retrait du
> seed → 0 tenant → jobs silencieusement inactifs). **À repointer sur KC** (ou le catalogue `company_%`). Phase 3.

Le `TenantProvisioningController` est appelé par AppBackend après création de l'org KC + ligne `companies`.

---

## 3. Ce qui reste hors DB (à traiter, pas encore fait proprement)

| Vecteur | Risque si oublié | Action |
|---|---|---|
| RabbitMQ | message consommé sans tenant → écrit dans le mauvais schéma | `TenantMessagePostProcessor` (déjà ébauché) stampe `companyId` en header à la publication ; **le consumer doit `TenantContext.set()` depuis le header AVANT tout accès DB**, et fail si absent. À vérifier dans chaque `@RabbitListener`. |
| Jobs `@Scheduled` | pas de ThreadLocal → NPE ou fuite | `TenantIterator` (déjà ébauché) : boucler sur les companies, `set()`/`clear()` autour de chaque itération. |
| WebSocket / STOMP | `WebSocketSecurityInterceptor` modifié | vérifier que la session porte le companyId et que les topics sont scindés par tenant. |
| MinIO | bucket partagé = fuite fichiers | bucket (ou préfixe) par company ; `MinioStorageService` déjà modifié → vérifier la clé. |
| Caches (`ErpLookupService` `CACHE_SCOPE="global"`) | cache d'un tenant servi à un autre | clé de cache = `companyId + ...`. Fini le `global`. |
| ERP par company | 1 ERP par tenant | `SystemSettings` doit être **par schéma** (déjà le cas si dans les tables tenant) → OK une fois l'isolation réelle. |
| Feign inter-services (`ServiceClientConfig`) | perte du tenant au hop | propager `X-Company-Id` sur les appels sortants (interceptor Feign). |

---

## 4. Fail-closed : la règle

`TenantContext` non résolu →
- requêtes **API authentifiées** : `403` (jamais de fallback `...0001`).
- endpoints **publics** (`/api/public/**`, tracking) : ils portent déjà le `deliveryId` ; le tenant doit être
  résolu depuis la ressource (lookup delivery → company) ou rester sur un schéma dédié — **à concevoir**, pas
  de default silencieux.
- **bootstrap/provisioning** : seul cas légitime sans tenant (opère sur `public`/`_admin`), réservé au rôle
  `SERVICE` sur route interne.

Retirer les deux `...0001` (Gateway L85, TenantContextFilter L36/42).

---

## 5. Plan d'exécution (phasé)

### Phase 0 — Nettoyage ✅ FAIT (2026-07-18)
1. ✅ **Supprimé** `V31__add_company_id_to_all_tables.sql`.
2. ✅ **Supprimé** `TenantRoutingDataSource.java` (Delivery, Driver, AppBackend) — dead code.
3. ✅ **Supprimé** `TenantSchemaFilter.java` (Delivery, Driver, AppBackend) — remplacé en Phase 1 par Hibernate.
4. ✅ Fallbacks `...0001` retirés → **fail-closed** :
   - Gateway `UserContextHeaderFilter` : `org_id` absent → **403** (plus de company par défaut).
   - `TenantContextFilter` (×4 services) : header absent → contexte **non-set** (trafic public/actuator OK) ;
     header **invalide** → **400**. Plus jamais de `...0001` injecté.
   - `TenantIterator` (×2) : commentaire corrigé + TODO(phase-1) sur le `setSchema` bugué (même défaut que
     l'ex-filter — à retirer une fois le connection provider en place).

> Décision actée : **`companyId = org_id Keycloak`** → schéma = `company_{org_id}`, le token porte la clé,
> aucune table de correspondance.

### Phase 1 — PoC isolation réelle sur **delivery-service** ✅ FAIT + PROUVÉ (2026-07-18)
5. ✅ `TenantIdentifierResolver` (`CurrentTenantIdentifierResolver`) + `SchemaMultiTenantConnectionProvider`
   (`MultiTenantConnectionProvider`) + `TenantSchema` (dérive `company_<32hex>`), wirés via
   `HibernatePropertiesCustomizer`. Le `search_path` est posé sur LA connexion qu'Hibernate donne à la
   transaction, reset au release. 1 seul pool.
6. ✅ `TenantSchemaProvisioner` durci : nom via `TenantSchema` (fix bug tirets UUID = SQL invalide) + rollback
   `DROP SCHEMA` si la migration échoue.
7. ✅ Seed par défaut retiré (V1 companies, V11 failure_reasons) → schémas provisionnés vides, seed manuel.
   DB delivery resettée (greenfield).
8. ✅ **Test de preuve PASSÉ.** Setup : KC org `default-company` wipée ; 2 orgs fraîches (`acme-sfax`
   483884a3…, `acme-tunis` d6d91078…) + 2 users (ADMIN+SERVICE, membres de leur org) ; 2 schémas provisionnés ;
   1 company distincte seedée par schéma. Résultat : `GET /api/admin/companies/me`
   → token sfax = **ACME Sfax**, token tunis = **ACME Tunis**. Même endpoint, isolation totale.
   Chaîne validée : claim `organization` (map avec id) → Gateway → `X-Company-Id` → `TenantContext` →
   connection provider → `search_path` → schéma. Config KC : mapper org `addOrganizationId=true`, scope
   `organization` mis en **default** sur admin-web (les logins PKCE portent le claim), direct-grants remis à OFF.

### Phase 2 — Généraliser aux services ✅ FAIT + PROUVÉ (2026-07-18)
9. ✅ Pattern Hibernate répliqué :
   - **AppBackend** (Flyway-less, `schema.sql` + ddl-auto:none) : 3 classes + provisioner durci (tirets+rollback).
   - **DriverService** : migré de `ddl-auto:update` → **Flyway** (baseline `V1__baseline.sql` dumpé du schéma
     live, ddl-auto:none) + 3 classes + provisioner Flyway + controller. Seed par défaut (`data.sql`) désactivé.
   - **ErpAdapter** : stateless (pas de DB) → pas de connection provider ; garde `TenantContextFilter` +
     propagation pour router l'ERP par company.
10. ✅ Feign : `X-Company-Id` propagé par les 4 `ServiceClientConfig` (depuis TenantContext pour les jobs +
    forward du header entrant).
11. ✅ **Test 2-tenants PASSÉ sur les 3 services stateful** (mêmes 2 orgs, schémas provisionnés + seed manuel) :
    - delivery `GET /api/admin/companies/me` → **ACME Sfax** / **ACME Tunis**
    - driver `GET /api/admin/drivers` → **Sfax Driver** / **Tunis Driver**
    - app-backend `GET /api/admin/users` → **Sfax Manager** / **Tunis Manager**
    Isolation totale, même endpoint, deux tokens.

### Phase 3 — Hors-DB ✅ FAIT + VÉRIFIÉ (2026-07-18)
12. ✅ RabbitMQ : `TenantInboundPostProcessor` (afterReceivePostProcessor) sur la container factory des **4
    services** → lit `X-Company-Id` du header et pose le TenantContext avant chaque listener (clear-then-set,
    fail-safe si header absent). Coexiste avec le retry. Le publish stampait déjà (TenantMessagePostProcessor).
13. ✅ `TenantIterator` (delivery + appbackend) réécrit : énumère les schémas `company_%` du **catalogue**
    (pas `public.companies` vidé) → hex→UUID, pose juste le TenantContext (plus de `setSchema` bugué, l'outbox
    utilise JPA donc le connection provider route). Corrige l'outbox qui bouclait sur 0 tenant.
14. ✅ MinIO : déjà isolé (préfixe `{companyId}/` sur chaque objet). Cache ERP : déjà per-tenant
    (`cacheScope()` = companyId). Rien à ajouter (fait par un agent précédent).
15. ✅ WebSocket : `EventPublisher` avait 4 broadcasts admin **globaux** (`/topic/admin.deliveries|routes|erp`)
    → passés par `tenantTopic()` = `/topic/company/{id}/...`. L'interceptor enforce déjà la subscription à sa
    propre company ; le front s'abonne déjà au topic tenant-scoped. Fuite temps réel cross-tenant fermée.

### Phase 4 — Onboarding orchestré ✅ FAIT + PROUVÉ (2026-07-18) — registre = Keycloak, pas de table partagée
16. ✅ `TenantOnboardingService` + `POST /internal/onboarding` (SERVICE) sur app-backend : 1 appel →
    crée l'org KC (`KeycloakAdminClient.createOrganization`) → provisionne le schéma sur **les 3 services**
    (local + delivery/driver via `/internal/tenants/{id}/provision`) → crée l'admin + membership. **Rollback**
    complet si une étape échoue (déprovision les schémas + supprime user + org). `companyId = org_id KC`.
    **Test E2E PASSÉ** : `{companyName:"ACME Sousse",...}` → 3 schémas + org + membre créés en 1 appel.
    Corrections trouvées au passage : driver rejetait le token service (audience validator → ajouté
    `app-backend`) ; membership KC en `application/json` ; rollback supprime aussi le user (sinon email orphelin).
17. ❌ **ABANDONNÉ** : switcher de company (multi-company). Décision : **1 admin = 1 company**, pas besoin.
18. ✅ **FAIT + PROUVÉ** : tracking public (`/api/public/track/**`, sans header). `PublicTrackingTenantFilter`
    + `PublicTenantResolver` : résout le tenant en scannant les schémas `company_%` pour le `deliveryId`
    (UUID global), avec cache (une delivery ne change jamais de tenant). Pose le TenantContext avant JPA.
    Couvre aussi le RMA public. **Test** : delivery seedée dans sfax → `GET /api/public/track/{id}` (no auth)
    → 200 "Client Sfax" ; id inconnu → 404. Zéro table partagée (KC reste le registre, catalogue = data).

### Phase 5 — Durcissement
17. Test cross-tenant automatisé en CI (le test de Phase 1 généralisé).
18. Revue : aucun `findAll()` non scoping-safe ne peut fuiter (avec schéma-par-tenant, c'est le search_path
    qui protège — donc l'audit porte sur « toute connexion a-t-elle toujours un search_path correct ? »).

---

## 6. Fichiers concernés (récap)

**À supprimer :** `V31__*.sql`, `TenantRoutingDataSource.java` (×3), `TenantSchemaFilter.java` (×3).

**À créer (par service) :** `SchemaTenantResolver` (CurrentTenantIdentifierResolver),
`SchemaMultiTenantConnectionProvider`, config JPA multi-tenancy.

**À garder/durcir :** `TenantContext`, `TenantContextFilter` (fail-closed), `TenantSchemaProvisioner`
(garde+rollback), `TenantProvisioningController` (verrou interne), `UserContextHeaderFilter` (retirer fallback),
`TenantMessagePostProcessor` + `TenantIterator` (à finir/auditer).

---

## 7. Test local (rappel)
- 2 companies → 2 schémas `company_<uuidA>` / `company_<uuidB>`.
- 2 users KC dans 2 orgs distinctes → 2 tokens portant `org_id` différents.
- Même endpoint, 2 tokens → 2 jeux de données disjoints. C'est le seul critère de succès.
