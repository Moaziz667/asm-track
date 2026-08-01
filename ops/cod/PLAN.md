# Plan — Encaissement à la livraison (COD)

> Statut : plan rédigé, mise en œuvre non commencée.
> Date : 1er août 2026.

---

## Pourquoi

**55 à 80 % des commandes en ligne en Tunisie sont payées en espèces à la livraison.**
En B2B distribution — le cas d'ASM Track — l'équivalent est le paiement au comptant du magasin,
en espèces ou par chèque, face au livreur.

Aujourd'hui, ASM Track n'en sait rien.

### Ce qui existe

| | État |
|---|---|
| `Order.totalAmount` + `currency` | ✅ synchronisés depuis l'ERP, affichés au livreur |
| Saisie de l'argent encaissé | ❌ aucun champ, nulle part |
| `ProofOfDelivery` | signature, photos, GPS — **aucun montant** |
| Clé i18n `labelCod` | ⚠️ traduite en 3 langues, **utilisée nulle part** |

Quelqu'un a prévu la fonction et ne l'a jamais construite. Le mot « COD » n'apparaît
ailleurs que dans un commentaire de `fcm_service.dart`.

### Le trou que ça laisse

Un livreur fait 12 arrêts et rentre avec 3 400 TND en poche. Entre l'encaissement et le
dépôt de l'argent, **personne ne sait combien il détient**. L'ERP l'ignore : il ne voit
l'argent qu'une fois enregistré en comptabilité, souvent le lendemain ou plus tard.

C'est le trou qu'ASM Track est le seul à pouvoir combler — il est le seul système présent
sur le terrain au moment où l'argent change de mains.

---

## Le principe qui gouverne tout

> **L'argent ne transite jamais par ASM Track.**

Pas de portefeuille, pas de solde, pas de virement, pas de reversement. Dès qu'une
plateforme détient ou achemine des fonds, elle relève de la réglementation des services de
paiement (BCT) — ce qui suppose un agrément.

L'argent physique circule : **magasin → livreur → caisse du dépôt → banque.**

ASM Track enregistre **qui détenait quoi, et quand**. C'est un *registre de garde*, pas un
système de paiement. Toute la conception découle de cette phrase.

Corollaire, sur le modèle de `ErpInvoiceService` :
*ASM déclenche et constate — l'ERP reste le livre de comptes.*

---

## Phase 0 — Le montant arrive de l'ERP

Deux champs sur `Order`, **en lecture seule**, alimentés par la synchronisation :

```
codRequired : boolean      ← l'ERP dit s'il faut encaisser
codAmount   : BigDecimal   ← combien
```

`codRequired` se déduit des **conditions de paiement** de l'ERP
(`sale.order.payment_term_id` côté Odoo). Paiement immédiat → `true`. 30 jours → `false`.

👉 Passe par le mapper de champs existant (24 champs, UI déjà en place).
**ASM ne calcule aucun montant.**

> **À décider** — lire les conditions de paiement, ou exiger un champ dédié dans l'ERP ?
> Recommandation : les conditions de paiement. C'est la donnée que le client tient déjà à
> jour ; un champ dédié serait un doublon qui dérive.

### Garde-fou devise

Si l'ERP renvoie une devise autre que `TND`, **on refuse l'encaissement** au lieu de
l'accepter en silence.

Ce n'est pas théorique : le bon de livraison affichait `6000.000 USD` parce que la devise
était recopiée de l'ERP sans contrôle. Sur un document, c'est gênant. Sur de l'argent
qu'un livreur doit compter, c'est une erreur de caisse garantie.

---

## Phase 1 — Le livreur constate

Nouvelle entité `CashCollection`, **une par livraison**.

| Champ | Rôle |
|---|---|
| `deliveryId` | |
| `amountExpected` | recopié à la création, **figé** — l'attendu ne bouge plus |
| `amountCollected` | ce que le livreur a effectivement pris |
| `method` | `CASH` / `CHEQUE` / `NONE` |
| `chequeNumber`, `chequeBank`, `chequeDate` | si chèque — indispensable en B2B tunisien |
| `reason` | si rien ou partiel → **catalogue de motifs existant** |
| `collectedAt`, `driverId` | |

**États :** `PENDING` → `COLLECTED` / `PARTIAL` / `REFUSED`

### Pourquoi une entité séparée du POD

Le POD est une preuve figée : il est écrit une fois et ne change plus. L'encaissement a un
cycle de vie qui continue après la livraison (remise, rapprochement, litige). Les mélanger
rendrait le POD mutable — et une preuve mutable n'est plus une preuve.

### Hors ligne

La saisie doit fonctionner sans réseau et se synchroniser ensuite.
`IdempotentOperation` existe déjà et couvre le rejeu.

---

## Phase 2 — La remise de caisse ⭐

**C'est le cœur du module, et ce qu'aucun ERP ne fait.**

`CashRemittance` :

```
driverId
openedAt, closedAt
expectedTotal   ← somme des CashCollection du livreur sur la période
declaredTotal   ← ce que le livreur déclare avoir
receivedTotal   ← ce que le caissier compte réellement
receivedBy
discrepancy     ← calculé, jamais saisi
note
```

**États :** `OPEN` → `DECLARED` → `RECEIVED` → `RECONCILED` (ou `DISPUTED`)

### Règles

- L'écart est **toujours calculé**, jamais saisi à la main.
- Chaque transition est **append-only**, horodatée, avec son auteur.
  L'argent est une surface de fraude : l'historique doit être immuable.
  Patron déjà en place → `DeliveryStatusHistory`.
- Une livraison échouée avec COD ne produit **aucune** collecte, et son attendu
  disparaît du total du livreur.

> **À décider** — une remise par tournée, ou par journée ?
> Recommandation : **par tournée**. Ça colle au terrain (le livreur rentre au dépôt) et ça
> limite le montant exposé à chaque instant.

---

## Phase 3 — Le pilotage

- **Bureau COD** — encaissements du jour, remises en attente, écarts à traiter.
- **Fiche livreur** — son encours de caisse.
- **Carte tableau de bord : « Cash en circulation »**
  Combien d'argent se trouve, *à cet instant*, dans les poches des livreurs.

Cette dernière métrique n'existe dans aucun ERP, et ne peut pas y exister : elle décrit un
état du terrain entre deux écritures comptables. C'est l'argument produit, et le point fort
de la soutenance.

---

## Phase 4 — Le retour vers l'ERP

**v1 : on n'écrit rien dans l'ERP.**

ASM expose un état de rapprochement ; le comptable enregistre le paiement lui-même dans
Odoo / ERPNext.

Écrire un `account.payment` dans la comptabilité d'un client est un acte lourd et peu
réversible. On y va quand le reste est éprouvé, et avec la même prudence que
`ErpInvoiceService` : *best-effort, on ne bloque jamais une livraison, et en cas d'échec on
dit à l'opérateur de le faire à la main.*

---

## Périmètre exclu (v1)

À écrire noir sur blanc pour éviter la dérive :

- **Retour avec remboursement** — un RMA qui rend de l'argent au client. Hors périmètre.
- **Paiement par carte / TPE mobile** — hors périmètre.
- **Encaissement partiel étalé** (le magasin paie le reste plus tard) — on enregistre un
  `PARTIAL` avec son motif, et le reliquat se règle hors ASM.
- **Multi-devise** — refusé par construction (voir Phase 0).

---

## Ordre d'exécution

```
BL depuis l'ERP  ──►  Phase 0  ──►  Phase 1  ──►  Phase 2  ──►  Phase 3
                         ▲
                  mapper existant
```

Le chantier « BL depuis l'ERP » passe en premier : il est court, il **supprime** du code, et
il touche le même mapper que la phase 0 réutilisera.

---

## Ce qui rend ce module solide

| Risque | Réponse |
|---|---|
| Fraude livreur | historique append-only, écart calculé, deux déclarations (livreur + caissier) |
| Devise fausse | refus à la source |
| Perte réseau au moment de l'encaissement | saisie hors ligne + idempotence |
| Livraison échouée | pas de collecte, attendu retiré du total |
| Compta du client corrompue | v1 n'écrit rien dans l'ERP |
