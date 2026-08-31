# Tournées

Une tournée est une **séquence ordonnée d'arrêts** confiée à un livreur et un véhicule pour une
journée.

---

## Le cycle de vie

```mermaid
stateDiagram-v2
    [*] --> DRAFT : création
    DRAFT --> DRAFT : ajouter, retirer, réordonner
    DRAFT --> VALIDATED : validation du dispatcher
    VALIDATED --> IN_PROGRESS : le livreur démarre
    IN_PROGRESS --> CLOSED : tous les arrêts terminés
    VALIDATED --> CANCELLED : annulation (motif requis)
    IN_PROGRESS --> CANCELLED : annulation (motif requis)
    CLOSED --> [*]
    CANCELLED --> [*]

    note right of DRAFT
        Seul état modifiable librement.
        Le livreur ne la voit pas encore.
    end note
    note right of CANCELLED
        Les arrêts non livrés retournent
        au pool ; les terminés sont gardés.
    end note
```

**La frontière `DRAFT` / `VALIDATED` est la décision structurante.** Tant qu'une tournée est en
brouillon, le dispatcher réorganise librement. À la validation, elle devient un engagement : le
livreur la voit, les SLA sont calculés, et toute modification passe par des opérations contrôlées.

!!! note "Annuler ne détruit pas"
    Une tournée annulée en cours renvoie ses arrêts **non livrés** au pool (`UNSCHEDULED`) et conserve
    les arrêts terminés. Le travail déjà fait n'est jamais perdu, et le reste redevient
    replanifiable.

---

## La construction d'une tournée

```mermaid
flowchart TD
    A["Livraisons UNSCHEDULED"] --> B["Filtres : zone, dépôt, créneau"]
    B --> C["Le dispatcher sélectionne"]
    C --> D["Choix livreur + véhicule"]
    D --> E{"Disponibles<br/>à cette date ?"}
    E -->|non| F["Écartés de la liste"]
    E -->|oui| G["Tournée DRAFT"]
    G --> H["Réordonner (glisser-déposer)"]
    G --> I["Optimiser (suggestion OSRM)"]
    I --> J["Le dispatcher accepte<br/>ou ajuste"]
    J --> K["Validation"]

    style I fill:#e3f2fd,stroke:#1565c0
    style K fill:#e8f5e9,stroke:#2e7d32
```

!!! info "Une tournée peut charger dans plusieurs dépôts"
    Elle reçoit alors un arrêt d'enlèvement par dépôt, et aucune livraison ne peut être placée avant
    l'enlèvement dont elle dépend. Voir [Dépôts](depots.md).

!!! tip "L'optimisation suggère, elle n'applique pas"
    L'endpoint `/optimize` renvoie un ordre proposé ; c'est le dispatcher qui l'accepte via
    `/stops/reorder`. Un « appliquer directement » a existé et a été supprimé, sans appelant : le
    dispatcher connaît des contraintes que l'algorithme ignore — un client qui n'ouvre qu'après 14 h,
    une rue en travaux.

---

## Les fenêtres horaires

```mermaid
sequenceDiagram
    autonumber
    actor D as Dispatcher
    participant R as RouteOptimizationService
    participant O as OSRM
    participant UI as Aperçu d'optimisation
    participant DB as PostgreSQL

    D->>R: demande une optimisation
    R->>O: matrice des durées et distances
    O-->>R: durée + distance par tronçon
    R-->>UI: ordre proposé + durée par tronçon
    UI->>UI: cumule depuis l'heure de départ<br/>→ fenêtre suggérée par arrêt
    D->>DB: accepte ou corrige, puis enregistre
```

**Le système n'enregistre jamais une heure d'arrivée qu'il a calculée lui-même.** Côté serveur,
`stop.etaAt` reste `null` : OSRM fournit la distance, la durée et la géométrie, et c'est l'aperçu
d'optimisation qui en dérive une **fenêtre suggérée** par arrêt. Ce qui est persisté, et ce sur quoi
le SLA se mesure ensuite, est la fenêtre que le dispatcher a acceptée ou corrigée.

**OSRM tourne en local**, sur les données OpenStreetMap de la Tunisie. Deux raisons : aucun appel
externe facturé par requête, et les temps de trajet reflètent le réseau routier réel plutôt qu'une
distance à vol d'oiseau.

!!! warning "Pourquoi la suggestion n'est pas un engagement"
    OSRM ne connaît ni le trafic du jour, ni les habitudes locales. Juger un livreur sur une durée
    calculée sur une carte reviendrait à l'évaluer sur une prévision que personne n'a validée. Une
    fenêtre lue et engagée par un dispatcher est, elle, une promesse assumée — d'où le choix de
    mesurer le SLA sur la fenêtre et non sur une ETA machine.

---

## Le rapport de tournée

À la clôture, un rapport consolide la journée : arrêts livrés, échoués, distance parcourue, respect
des SLA, encaissements. Il est exportable en PDF.

```mermaid
flowchart LR
    A["Tournée CLOSED"] --> B["RouteReportService"]
    B --> C["Agrégats : livrés, échoués,<br/>km, SLA, espèces"]
    C --> D["route_report<br/>(figé en base)"]
    D --> E["PDF"]

    style D fill:#e8f5e9,stroke:#2e7d32
```

**Le rapport est figé en base, pas recalculé.** Un rapport recalculé six mois plus tard donnerait des
chiffres différents — les SLA évoluent, les référentiels changent. Un rapport de journée doit dire ce
qui s'est passé **ce jour-là**.
