# Messagerie et événements

> Décision et alternatives écartées : [ADR-004](../adr/004-outbox-transactionnel.md).

---

## La topologie réelle

```mermaid
flowchart LR
    subgraph DEL["DeliveryMicroservice"]
        D1["OutboxProcessor"]
        D2["AuditEventConsumer"]
        D3["DriverStatusConsumer"]
    end
    subgraph ERP["ErpAdapterService"]
        E1["ErpSyncCommandConsumer"]
        E2["ErpSyncResultPublisher"]
    end
    subgraph DRV["DriverService"]
        V1["publie les statuts"]
        V2["consomme les positions"]
    end
    subgraph AB["AppBackend"]
        A1["publie l'audit"]
        A2["IamCommandApplier"]
    end

    X1{{"erp.sync.exchange"}}
    X2{{"erp.sync.result.exchange"}}
    X3{{"driver.events"}}
    X4{{"driver.commands"}}
    X5{{"audit.exchange"}}
    X6{{"iam.exchange"}}
    DLX{{"*.dlx<br/>files mortes"}}

    D1 -->|"erp.sync.command"| X1 --> E1
    E2 -->|"erp.sync.result"| X2 --> D1
    V1 -->|"driver.status.changed"| X3 --> D3
    D1 -->|"driver.location.update"| X4 --> V2
    A1 & D2 -->|"audit.log"| X5 --> D2
    A2 -->|"iam.command"| X6
    X3 -.échecs.-> DLX
    X5 -.échecs.-> DLX

    style X1 fill:#f3e5f5,stroke:#7b1fa2
    style X2 fill:#f3e5f5,stroke:#7b1fa2
    style DLX fill:#ffebee,stroke:#c62828
```

**Pourquoi ce diagramme.** C'est la seule vue qui montre que la synchronisation ERP est une **boucle
en deux temps** — une commande part, un résultat revient — et non un appel.

**Observations importantes.**

- **Deux échanges pour l'ERP**, pas un. La commande et le résultat voyagent séparément, ce qui permet
  à l'adaptateur de répondre longtemps après, sans que personne n'attende.
- **Des files mortes (`.dlx`) sur les flux critiques.** Un message qui échoue définitivement n'est pas
  perdu : il est consultable et rejouable depuis le back-office.
- Chaque message porte l'en-tête `X-Company-Id` — voir [Multi-tenant](multi-tenant.md).

---

## Le patron Outbox

### Le problème

```mermaid
flowchart LR
    A["UPDATE deliveries<br/>status = DELIVERED"] --> B{"COMMIT<br/>réussit ?"}
    B -->|oui| C["publier vers l'ERP"]
    C --> D{"le service<br/>survit ?"}
    D -->|non| E["Livraison enregistrée<br/>ERP jamais informé"]
    D -->|oui| F["cohérent"]

    style E fill:#ffebee,stroke:#c62828,stroke-width:3px
```

La fenêtre entre le `COMMIT` et le `publish` est courte mais **réelle**. Un arrêt à cet instant perd
l'événement, sans trace. Le défaut ne se manifeste que sous charge ou pendant un incident —
exactement quand on ne peut pas l'analyser.

### La solution

```mermaid
sequenceDiagram
    autonumber
    participant S as Service métier
    participant DB as PostgreSQL
    participant OP as OutboxProcessor<br/>(toutes les 20 s)
    participant MQ as RabbitMQ
    participant E as ErpAdapterService

    rect rgb(232, 245, 233)
        Note over S,DB: Une seule transaction
        S->>DB: UPDATE deliveries SET status='DELIVERED'
        S->>DB: INSERT INTO outbox_event (status='PENDING')
        S->>DB: COMMIT
    end
    Note over S,DB: les deux réussissent, ou aucun

    OP->>DB: SELECT … WHERE status='PENDING' FOR UPDATE SKIP LOCKED
    DB-->>OP: lot d'événements (max 10)
    OP->>DB: status='PROCESSING'
    OP->>MQ: publie
    MQ->>E: erp.sync.command
    OP->>DB: status='PROCESSED'
```

!!! success "La garantie n'est pas laissée à la vigilance de l'appelant"
    ```java
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String type, Object payload) { … }
    ```

    `MANDATORY` fait **échouer** l'appel s'il n'existe pas déjà une transaction. Écrire dans l'outbox
    hors d'une transaction métier n'est donc pas une erreur qu'on peut commettre : le conteneur la
    refuse. La propriété centrale du patron est vérifiée par le framework, pas seulement documentée.

**`SKIP LOCKED` mérite un mot.** Il permet à plusieurs instances du service de traiter l'outbox en
parallèle sans se marcher dessus : chacune saute les lignes verrouillées par une autre. Sans lui,
deux instances enverraient le même événement deux fois.

---

## La reprise sur échec

```mermaid
stateDiagram-v2
    [*] --> PENDING : enqueue()
    PENDING --> PROCESSING : claim (SKIP LOCKED)
    PROCESSING --> PROCESSED : succès
    PROCESSING --> PENDING : échec, tentative ≤ 15
    PROCESSING --> FAILED : échec, tentative > 15
    PROCESSING --> PENDING : bloqué > 5 min (reprise après crash)
    FAILED --> PENDING : rejeu manuel (back-office)
    PROCESSED --> [*]

    note right of PENDING
        Attente exponentielle :
        2ⁿ × 5 s, plafonnée à 4 h
    end note
    note right of FAILED
        File morte, visible et
        rejouable — jamais perdu
    end note
```

**Ce que la politique couvre réellement : 21,7 heures.**

| Tentative | Attente | Cumul |
|---|---|---|
| 1 → 11 | 10 s → 2 h 50 | 5,7 h |
| 12 → 15 | 4 h chacune | **21,7 h** |

!!! warning "Une affirmation corrigée"
    Le code annonçait « 45+ hours, fully protecting against weekend outages ». La somme n'avait
    jamais été calculée : elle vaut **21,7 h**. Atteindre 60 heures — une panne du vendredi soir
    résolue le lundi — demanderait **25 tentatives**.

    21,7 h couvrent confortablement une panne nocturne et entièrement une journée de travail. Le
    compromis est assumé : un événement en échec depuis une journée relève plus souvent d'une
    configuration cassée que d'une indisponibilité, et la file morte le garde visible.

---

## Deux étages de reprise, à ne pas confondre

Ces 15 tentatives ne concernent **que la publication** : elles couvrent le cas où RabbitMQ est
injoignable. Une fois le message publié, l'outbox a terminé son travail et passe l'événement à
`PROCESSED` — quoi qu'il advienne ensuite chez l'ERP.

La suite relève d'un **second compteur**, côté consommateur, aux valeurs bien plus courtes. Les
confondre fait croire qu'une panne d'Odoo est tolérée pendant 21 heures ; elle l'est en réalité
pendant **30 secondes**.

| | Étage outbox | Étage consommateur |
|---|---|---|
| **Qui** | `OutboxProcessor` (Service Livraison) | `ErpSyncCommandConsumer` (Adaptateur ERP) |
| **Couvre la panne de** | RabbitMQ | l'ERP (Odoo / ERPNext) |
| **Tentatives** | 15 | **5** |
| **Attentes** | 2ⁿ × 5 s, plafond 4 h | 2 s → 4 s → 8 s → 16 s, plafond 30 s |
| **Durée totale** | 21,7 h | **≈ 30 s** |
| **À l'épuisement** | `FAILED` en base | message vers `erp.sync.command.dlq` |

!!! warning "Odoo n'est toléré que 30 secondes"
    Passé ce délai, la commande part en file de rebut. Elle n'est pas perdue : le consommateur de
    rebut publie aussitôt un résultat en échec, la commande passe à `SYNC_FAILED` et l'administrateur
    est notifié — elle ne reste jamais bloquée sur « synchronisation en cours ». Mais la reprise
    devient **manuelle** (bouton *Resynchroniser*), elle n'est plus automatique.

    C'est un choix à assumer : une panne ERP de plus de trente secondes demande un geste humain.

Un troisième compteur existe, à ne pas ajouter aux deux autres : `ErpResyncService` autorise 10
tentatives, mais il s'agit de la reprise **déclenchée par un opérateur** sur une commande déjà
`SYNC_FAILED` — une escalade, pas une boucle automatique.

---

## Le cycle complet d'une synchronisation ERP

```mermaid
sequenceDiagram
    autonumber
    participant L as Livreur
    participant D as DeliveryMicroservice
    participant DB as PostgreSQL
    participant MQ as RabbitMQ
    participant E as ErpAdapterService
    participant O as Odoo

    L->>D: POD validée
    rect rgb(232, 245, 233)
        D->>DB: statut = DELIVERED
        D->>DB: outbox_event PENDING
        D->>DB: order.erpSyncStatus = PENDING_SYNC
    end
    D-->>L: 200 — le livreur repart
    Note over D,MQ: (asynchrone à partir d'ici)
    D->>MQ: erp.sync.command
    MQ->>E: consomme
    alt Odoo répond
        E->>O: valide le transfert (JSON-RPC)
        O-->>E: ok
        E->>MQ: erp.sync.result (succès)
        MQ->>D: consomme
        D->>DB: order.erpSyncStatus = SYNCED
    else Odoo injoignable
        loop 5 tentatives — 2 s, 4 s, 8 s, 16 s
            E->>O: valide le transfert
            O--xE: échec
        end
        E->>MQ: erp.sync.command.dlq
        MQ->>E: consomme la file de rebut
        E->>MQ: erp.sync.result (échec)
        MQ->>D: consomme
        D->>DB: order.erpSyncStatus = SYNC_FAILED
        D->>D: notifie l'administrateur
    end
```

**Le point essentiel : le livreur n'attend jamais l'ERP.** Il valide, l'écran répond, il repart. Un
ERP indisponible ralentit la synchronisation sans jamais bloquer le terrain.

**La contrepartie**, à assumer dans l'interface : la cohérence est **à terme**, pas immédiate. C'est
ce que reflète l'état `PENDING_SYNC` sur la commande, plutôt que de laisser croire à une écriture
instantanée.
