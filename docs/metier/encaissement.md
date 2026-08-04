# Encaissement contre remboursement (COD)

En Tunisie, une part importante des livraisons est payée **en espèces à la remise**. Le livreur
collecte l'argent et le rapporte au dépôt.

---

## Le principe qui gouverne tout le module

!!! quote "ASM Track enregistre une garde, jamais une écriture comptable"
    La plateforme n'est pas un établissement de paiement. Elle ne détient pas de fonds, n'ouvre pas
    de compte, ne compense pas. Elle répond à une seule question, à tout instant :

    **« Qui détient physiquement cet argent en ce moment ? »**

    La comptabilité reste dans l'ERP du client. C'est le même principe que pour le bon de livraison :
    le système qui possède un document possède sa conformité.

---

## Les deux objets

```mermaid
erDiagram
    DELIVERY ||--o| CASH_COLLECTION : "produit à la livraison"
    CASH_COLLECTION }o--o| CASH_REMITTANCE : "regroupée dans"

    CASH_COLLECTION {
        uuid delivery_id
        uuid driver_id
        decimal amount_expected "figé à la création"
        decimal amount_collected "saisi par le livreur"
        string status "PENDING COLLECTED PARTIAL REFUSED"
        timestamp collected_at
        uuid remittance_id "NULL tant qu'en poche"
    }
    CASH_REMITTANCE {
        uuid driver_id
        decimal expected_total "calculé par la plateforme"
        decimal declared_total "annoncé par le livreur"
        decimal received_total "compté au dépôt"
        decimal discrepancy "dérivé, jamais saisi"
        string status "OPEN DECLARED RECEIVED DISPUTED RECONCILED"
    }
```

**`amount_expected` est figé à la création.** Si le montant de la commande change ensuite dans l'ERP,
la collecte garde ce que le livreur devait réellement encaisser ce jour-là.

**`discrepancy` est dérivé, jamais accepté d'un appelant.** Un écart qu'on peut saisir est un écart
qu'on peut mettre à zéro.

---

## Le parcours complet

```mermaid
sequenceDiagram
    autonumber
    actor L as Livreur
    actor C as Comptoir (dépôt)
    participant API as Backend

    Note over L,API: 1 · Sur le terrain
    L->>API: POD + montant encaissé
    API->>API: cash_collection (COLLECTED / PARTIAL / REFUSED)
    Note over API: l'argent est « en circulation »

    Note over L,API: 2 · Au retour au dépôt
    L->>API: déclare le total remis
    API->>API: expected_total calculé depuis les collectes
    Note over API: remise DECLARED

    Note over C,API: 3 · Le comptage, par quelqu'un d'autre
    C->>API: montant réellement compté
    alt Le compteur est le déclarant ou le livreur
        API-->>C: 400 CASH_SELF_RECEIVE
    end
    API->>API: discrepancy = compté − attendu
    alt écart nul
        Note over API: RECONCILED
    else écart
        Note over API: DISPUTED
    end

    Note over C,API: 4 · Solder un écart
    C->>API: justification écrite (obligatoire)
    Note over API: RECONCILED
```

---

## La règle des deux personnes

```mermaid
flowchart TD
    A["Demande de comptage"] --> B{"Le compteur est-il<br/>le déclarant ?"}
    B -->|oui| R1["400 CASH_SELF_RECEIVE"]
    B -->|non| C{"Le compteur est-il<br/>le livreur ?"}
    C -->|oui| R2["400 CASH_SELF_RECEIVE"]
    C -->|non| D["Comptage enregistré"]

    style R1 fill:#ffebee,stroke:#c62828
    style R2 fill:#ffebee,stroke:#c62828
    style D fill:#e8f5e9,stroke:#2e7d32
```

!!! danger "C'est le module tout entier"
    Le livreur annonce un chiffre ; **quelqu'un d'autre compte**. Sans cette séparation, le module
    est un système déclaratif avec des colonnes en plus — un livreur qui confirme sa propre remise a
    simplement tapé un nombre deux fois, et toutes les tables autour deviennent décoratives.

**La justification d'un écart est obligatoire.** Un écart clos sans explication est indiscernable
d'un écart dissimulé, et cette différence est la seule chose sur laquelle un audit pourra s'appuyer.

---

## L'argent en circulation

```mermaid
flowchart LR
    A["Collectes avec<br/>remittance_id NULL"] --> C["En circulation"]
    B["Collectes rattachées à une remise<br/>OPEN ou DECLARED"] --> C
    D["Collectes d'une remise<br/>RECONCILED"] -.->|"plus en circulation"| E["Soldé"]

    style C fill:#fff3e0,stroke:#ef6c00
    style E fill:#e8f5e9,stroke:#2e7d32
```

```java
String STILL_HELD = """
    (c.remittanceId IS NULL
     OR EXISTS (SELECT 1 FROM CashRemittance r WHERE r.id = c.remittanceId
                AND r.status IN (OPEN, DECLARED)))
    """;
```

**Une collecte déclarée mais pas encore comptée est toujours en circulation.** Le livreur l'a
annoncée, personne ne l'a vérifiée : l'argent est encore chez lui. Ne compter que les collectes sans
remise donnerait un chiffre faux dès qu'un livreur déclare.

!!! tip "Aucun ERP ne sait produire ce chiffre"
    Il décrit un **état du terrain entre deux écritures comptables**. C'est précisément la valeur
    ajoutée de la plateforme sur ce sujet.

---

## Le garde-fou sur la devise

```java
private static final String COLLECTABLE_CURRENCY = "TND";
```

Une commande en devise étrangère **n'est pas encaissable en espèces** par un livreur en Tunisie. Le
drapeau COD est ignoré, avec un avertissement journalisé, plutôt que de demander au livreur de
collecter un montant dans une monnaie qu'il ne manipule pas.

De même, un montant nul ou négatif désactive l'encaissement : un COD à zéro n'est pas un COD.
