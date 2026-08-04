# Cycle de vie d'une livraison

C'est l'objet central de la plateforme. Tout le reste — tournées, retours, encaissement — gravite
autour de ces états.

---

## La machine à états

```mermaid
stateDiagram-v2
    [*] --> UNSCHEDULED : import depuis l'ERP
    UNSCHEDULED --> SCHEDULED : affectée à un livreur<br/>(tournée ou prise directe)
    SCHEDULED --> PICKED_UP : colis récupéré au dépôt
    PICKED_UP --> IN_TRANSIT : départ vers le destinataire
    IN_TRANSIT --> DELIVERED : preuve de livraison complète
    IN_TRANSIT --> PARTIALLY_DELIVERED : une partie seulement
    IN_TRANSIT --> FAILED : motif d'échec
    IN_TRANSIT --> AWAITING_HANDOFF : transfert vers un autre livreur
    AWAITING_HANDOFF --> IN_TRANSIT : réception confirmée
    SCHEDULED --> UNSCHEDULED : le livreur se désiste
    UNSCHEDULED --> CANCELLED : annulation côté ERP
    SCHEDULED --> CANCELLED : annulation administrative

    DELIVERED --> [*]
    PARTIALLY_DELIVERED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]

    note right of UNSCHEDULED
        Le « pool » : personne
        ne la porte encore
    end note
    note right of AWAITING_HANDOFF
        Deux livreurs, un jeton :
        la garde ne se perd jamais
    end note
```

**Pourquoi ce diagramme.** Neuf états et leurs transitions légales tiennent en une image ; en texte,
il faut trois pages et on rate les transitions inverses.

**Observations importantes.**

- **`UNSCHEDULED` est un pool, pas une erreur.** Une livraison qui y retourne (désistement) est
  reprenable par un autre livreur.
- **Les états terminaux sont quatre**, et `PARTIALLY_DELIVERED` en fait partie : une livraison
  partielle est **close**, le reliquat vit dans une autre livraison créée par l'ERP.
- **`AWAITING_HANDOFF` existe pour que la garde ne soit jamais floue.** Sans lui, un colis passé de
  main en main serait « chez A » dans le système et « chez B » en réalité.

!!! danger "Erreur classique"
    Ajouter une transition « pour simplifier », par exemple `SCHEDULED → DELIVERED`. Chaque saut
    d'état supprime un horodatage que les SLA, les rapports de tournée et les litiges utilisent. Les
    transitions sont vérifiées côté service : `assertStatus(delivery, PICKED_UP, "start transit")`.

---

## Qui déclenche quoi

```mermaid
sequenceDiagram
    autonumber
    actor D as Dispatcher
    actor L as Livreur
    participant API as Backend
    participant E as ERP

    E->>API: commande prête (import)
    Note over API: UNSCHEDULED
    D->>API: construit la tournée, affecte
    Note over API: SCHEDULED
    L->>API: pickup au dépôt
    Note over API: PICKED_UP
    L->>API: transit (+ position GPS)
    Note over API: IN_TRANSIT — calcul de l'itinéraire et de l'ETA
    alt Livraison réussie
        L->>API: preuve : photos, signature, (encaissement)
        Note over API: DELIVERED
        API->>E: valider le transfert
    else Livraison partielle
        L->>API: quantités réellement remises
        Note over API: PARTIALLY_DELIVERED
        API->>E: transfert partiel → reliquat créé
    else Échec
        L->>API: motif (client absent, refus…)
        Note over API: FAILED
        API->>E: signaler l'échec
    end
```

**Le point à retenir : l'ERP est informé au moment terminal, jamais avant.** Les états intermédiaires
sont une affaire de terrain, pas de comptabilité.

---

## Le service, découpé par phase

Le parcours livreur était porté par une classe unique de 1 192 lignes et 24 dépendances. Elle est
découpée selon les **moments réels du terrain** :

```mermaid
flowchart TB
    C["DriverDeliveryController"]
    C --> S1["DriverDeliveryService<br/>accepter · pickup · transit · compléter · POD"]
    C --> S2["DriverIncidentService<br/>échouer · abandonner · signaler"]
    C --> S3["DriverHandoffService<br/>transfert de garde"]
    S1 & S2 & S3 --> SH["DeliveryTransitionSupport<br/>propriété + historique + SLA"]
    S1 & S2 & S3 --> M["DriverDeliveryMapper<br/>la vue renvoyée au mobile"]

    style SH fill:#e8eaf6,stroke:#3f51b5
    style M fill:#e8eaf6,stroke:#3f51b5
```

**`DeliveryTransitionSupport` est partagé, et ce n'est pas un détail.** Il porte trois opérations que
toute action de livreur effectue :

| Opération | Rôle |
|---|---|
| `loadAndAuthorize` | charge la livraison **et refuse à quiconque n'est pas le livreur qui la porte** |
| `assertStatus` | refuse une transition qui ne part pas du bon état |
| `appendHistory` | inscrit une ligne d'historique **et rafraîchit le SLA** |

!!! warning "Pourquoi ne pas les dupliquer par phase"
    Le contrôle de propriété est la **seule** règle empêchant un livreur de toucher la livraison d'un
    autre. Il doit avoir exactement une définition. Le rafraîchissement du SLA voyage avec
    l'historique volontairement : enregistrer le changement et re-dériver le SLA sont un seul acte —
    les séparer, c'est garantir qu'un jour l'un des deux sera oublié.

---

## La preuve de livraison

```mermaid
flowchart TD
    A["Le livreur remplit le formulaire"] --> B["Photos → MinIO<br/>(hors transaction)"]
    B --> C{"Dépôt réussi ?"}
    C -->|non| D["503 — réessayez<br/>rien n'est écrit"]
    C -->|oui| E["Transaction unique"]
    E --> F["proof_of_delivery"]
    E --> G["statut DELIVERED / PARTIALLY_DELIVERED"]
    E --> H["encaissement si COD"]
    E --> I["outbox_event → ERP"]

    style D fill:#ffebee,stroke:#c62828
    style E fill:#e8f5e9,stroke:#2e7d32
```

Le détail de l'ordre photos/transaction est expliqué dans [Stockage](../architecture/stockage.md).

**Une livraison partielle dérive son état des quantités**, elle n'est pas déclarée : si aucune ligne
n'est remise, le résultat est un **échec**, pas une livraison partielle vide — ce qui éviterait de
pousser un transfert vide à l'ERP.

---

## Le transfert de garde

```mermaid
sequenceDiagram
    autonumber
    actor A as Livreur A
    actor B as Livreur B
    participant API as Backend

    A->>API: demande un jeton de transfert
    API-->>A: jeton + expiration
    A->>A: affiche un QR code
    B->>B: scanne le QR
    B->>API: confirme avec le jeton (+ position)
    API->>API: vérifie le jeton et son expiration
    API-->>B: la livraison lui appartient
    Note over API: AWAITING_HANDOFF → IN_TRANSIT
```

**Pourquoi un jeton et pas un simple bouton.** Le transfert doit prouver une **remise physique**. Un
jeton à durée limitée, affiché par A et scanné par B, atteste que les deux étaient au même endroit au
même moment. Un bouton « j'ai donné le colis » n'atteste rien.
