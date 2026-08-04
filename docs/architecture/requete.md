# Cycle de vie d'une requête

Comprendre ce trajet, c'est comprendre comment la sécurité et l'isolation des clients tiennent
ensemble. Rien d'autre dans la documentation ne remplace cette page.

---

## Le trajet complet

```mermaid
sequenceDiagram
    autonumber
    participant C as Client<br/>(navigateur / mobile)
    participant GW as API Gateway
    participant KC as Keycloak
    participant F as TenantContextFilter<br/>(dans le service)
    participant S as Service
    participant H as Hibernate
    participant DB as PostgreSQL

    C->>GW: GET /api/v1/admin/deliveries<br/>Authorization: Bearer …
    GW->>GW: 1. Supprime tout X-User-* et X-Company-Id entrant
    GW->>KC: 2. Valide la signature (JWKS, RS256)
    KC-->>GW: clés publiques
    GW->>GW: 3. Évalue rbac-policy.json
    alt Aucune règle ne correspond
        GW-->>C: 403 (fail-closed)
    end
    GW->>GW: 4. Extrait la claim « organization »
    alt Absente, multiple ou non-UUID
        GW-->>C: 403
    end
    GW->>S: 5. Requête + X-User-Id + X-Company-Id
    S->>F: 6. Filtre d'entrée
    F->>F: Pose TenantContext (ThreadLocal) + MDC
    alt Chemin métier sans X-Company-Id
        F-->>C: 403 « No tenant context »
    end
    F->>S: 7. Le contrôleur s'exécute
    S->>S: 8. Ré-évalue rbac-policy.json (même fichier)
    S->>H: 9. Requête JPA
    H->>DB: SET search_path TO "company_<32hex>"
    DB-->>H: données du seul locataire
    H-->>S: entités
    S-->>C: 200
```

**Pourquoi ce diagramme.** Il rend visibles quatre contrôles qu'on ne voit nulle part en lisant une
classe isolée, et il montre que l'isolation ne repose pas sur la discipline du développeur.

---

## Les quatre contrôles, dans l'ordre

### 1 · Nettoyage des en-têtes — anti-usurpation

```java
copiedHeaders.remove("X-User-Id");
copiedHeaders.remove("X-User-Role");
copiedHeaders.remove("X-Company-Id");
// …puis la requête est décorée avec cette copie
```

!!! danger "Ce que ça empêche"
    Sans cette étape, n'importe qui pourrait envoyer `X-Company-Id: <uuid d'un concurrent>` et lire
    ses données. Les en-têtes de contexte ne sont **jamais** acceptés de l'extérieur : ils sont
    reconstruits à partir du jeton, à chaque requête.

### 2 · Validation du jeton

Signature RS256 vérifiée contre le JWKS de Keycloak. La gateway ne détient aucun secret partagé.

### 3 · Autorisation déclarative

Le fichier `rbac-policy.json` est évalué. Les règles sont **ordonnées**, premier match gagnant, et
**l'absence de règle vaut refus**. Détail complet : [Autorisation](autorisation.md).

### 4 · Résolution du locataire

```mermaid
flowchart TD
    A["Claim « organization » du JWT"] --> B{"Combien<br/>d'organisations ?"}
    B -->|"aucune"| R1["403 — pas de locataire"]
    B -->|"plusieurs"| R2["403 — locataire ambigu"]
    B -->|"exactement une"| C{"Est-ce un UUID ?"}
    C -->|"non"| R3["403 — identifiant invalide"]
    C -->|"oui"| D["Injecte X-Company-Id"]

    style R1 fill:#ffebee,stroke:#c62828
    style R2 fill:#ffebee,stroke:#c62828
    style R3 fill:#ffebee,stroke:#c62828
    style D fill:#e8f5e9,stroke:#2e7d32
```

**Le cas « plusieurs organisations » mérite une explication.** Un utilisateur appartenant à deux
organisations produirait un locataire **différent selon l'ordre d'itération de la map de claims** —
donc un basculement silencieux d'un client à l'autre, d'une requête à la suivante. Le refus explicite
est préférable à un comportement non déterministe sur une frontière de données.

---

## Pourquoi l'autorisation est évaluée deux fois

```mermaid
flowchart LR
    C["Client"] --> GW["Gateway<br/>évaluation n°1"]
    GW --> S["Service<br/>évaluation n°2"]
    X["Appelant interne<br/>ou réseau compromis"] -.contourne la gateway.-> S

    style GW fill:#fff3e0,stroke:#ef6c00
    style S fill:#e8f5e9,stroke:#2e7d32
    style X fill:#ffebee,stroke:#c62828,stroke-dasharray: 5
```

Une seule évaluation à la gateway ferait reposer la sécurité sur le fait que **personne n'atteint
jamais un service directement** — une propriété du déploiement, pas du code. En développement, où les
services écoutent sur leurs ports, elle est déjà fausse.

Les deux évaluations lisent **le même fichier**, répliqué à l'identique. Elles ne peuvent pas
diverger.

---

## Le cas particulier : le suivi public

```mermaid
sequenceDiagram
    autonumber
    participant D as Destinataire
    participant GW as Gateway
    participant F1 as TenantContextFilter
    participant F2 as PublicTrackingTenantFilter
    participant S as PublicTrackingService

    D->>GW: GET /api/v1/public/track/{deliveryId}
    Note over GW: Aucun jeton — chemin permitAll
    GW->>F1: requête sans X-Company-Id
    Note over F1: /api/v1/public/ est déclaré<br/>« sans locataire » : laisse passer
    F1->>F2: 
    F2->>F2: Résout le locataire depuis l'identifiant de livraison
    F2->>S: TenantContext posé
    S-->>D: statut, ETA, position du livreur
```

**Le point à comprendre.** Ce chemin n'a pas de jeton, donc pas de claim `organization`. Le locataire
est déduit **de l'identifiant de livraison lui-même**, plus bas dans la chaîne. C'est pourquoi
`/api/v1/public/` figure dans les chemins exemptés de la configuration :

```yaml
asm:
  tenant:
    tenant-less-prefixes: /api/v1/public/,/api/v1/auth/,/api/v1/dev/
```

!!! warning "Le compromis assumé"
    L'identifiant de livraison **est** le jeton d'accès. C'est le modèle des transporteurs réels
    (lien de suivi partageable), et un UUIDv4 n'est pas devinable. Mais la réponse contient le nom du
    livreur, son téléphone et sa position en temps réel : un lien qui fuite permet de suivre une
    personne. D'où une limite de débit — 120 appels par 10 minutes et par couple IP/livraison.

---

## À retenir

| Question | Réponse |
|---|---|
| Où le locataire est-il décidé ? | À la **gateway**, depuis le jeton. Jamais depuis un en-tête client. |
| Que se passe-t-il si un service reçoit une requête sans locataire ? | **403**, sauf sur les chemins déclarés sans locataire. |
| Où l'isolation est-elle appliquée ? | Par PostgreSQL, via `SET search_path` — pas par un `WHERE` applicatif. |
| Que se passe-t-il si un endpoint n'est pas dans la politique ? | Il est **inaccessible**. Une erreur se manifeste en 403 pendant le développement, jamais en fuite. |
