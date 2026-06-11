# Synchronisation ASM ↔ ERP (Odoo) — guide des flux

Ce document explique, en langage simple, comment ASM Track tient son ERP (Odoo) à jour
après chaque événement de livraison. Public visé : dispatcheur, responsable
logistique — pas besoin d'être développeur.

> **Idée clé.** ASM n'écrit jamais « en direct » dans Odoo. Il **dépose une consigne**
> dans une file d'attente fiable (le *bus*), et un service dédié l'applique à Odoo, puis
> renvoie un **accusé** (réussi / échoué). Ce mécanisme garantit qu'aucune consigne
> n'est perdue, même si Odoo est momentanément indisponible (panne, week-end).

---

## Les briques (en une phrase chacune)

- **La file fiable (outbox).** Quand ASM décide qu'une info doit partir vers Odoo, il
  l'enregistre d'abord dans sa propre base, dans la même opération que le changement
  métier. Donc : soit les deux sont enregistrés, soit aucun — jamais d'envoi fantôme.
- **Le bus (RabbitMQ).** Transporte la consigne d'ASM vers l'adaptateur Odoo.
- **L'adaptateur ERP.** Le service qui parle réellement à Odoo et renvoie l'accusé.
- **L'accusé (résultat).** Revient à ASM et fait passer la commande de
  « En cours de synchro » à **Synchronisé** ou **Échec de synchro**.

États de synchro d'une commande : `PENDING_SYNC` (en cours) → `SYNCED` (ok) /
`SYNC_FAILED` (échec, un humain doit regarder).

---

## 1. Livraison complète

Le client reçoit tout ce qui était prévu.

1. Le chauffeur valide la livraison dans l'app.
2. ASM passe la livraison en **LIVRÉE** et marque la commande « en cours de synchro ».
3. ASM dépose la consigne « valider le bon de livraison » dans la file.
4. **Ensuite seulement**, si une preuve de livraison existe (photo / signature), ASM
   dépose la consigne « preuve de livraison ». *L'ordre compte : le mouvement de stock
   part toujours avant la preuve.*
5. L'adaptateur applique le tout dans Odoo et renvoie l'accusé → commande **Synchronisée**.

## 2. Livraison partielle

Le client reçoit une partie seulement.

1. Le chauffeur saisit, ligne par ligne, la quantité réellement remise.
2. ASM **décide lui-même** du statut à partir de ces quantités (il ne fait pas
   confiance à un simple bouton de l'app) :
   - 0 article remis → **ÉCHEC** (voir flux 4) ;
   - tout remis → **LIVRÉE** (flux 1) ;
   - une partie remise → **PARTIELLEMENT LIVRÉE**.
3. Les quantités sont bornées : on ne peut jamais déclarer plus que la quantité commandée.
4. ASM envoie la livraison partielle à Odoo. Odoo crée alors un **reliquat** (un nouveau
   bon pour ce qui reste à livrer).
5. À réception de l'accusé, ASM crée automatiquement une **nouvelle livraison de
   reliquat** (même commande), avec son propre cycle de vie. Elle est créée **une seule
   fois**, même si l'accusé est reçu en double.

## 3. Article refusé (≠ manquant)

Un client peut accepter une partie et **refuser** le reste à la porte.

- Un article **manquant / en rupture** → reliquat (on le re-livrera).
- Un article **refusé** → **pas de reliquat** : le client n'en veut pas, on ne le
  re-livre pas automatiquement. (S'il faut le récupérer, on passe par un **retour**, flux 7.)

ASM distingue les deux et ne crée un reliquat que s'il reste un vrai manquant à re-livrer.

## 4. Échec de livraison

Le chauffeur n'a rien pu remettre (client absent, adresse fausse, refus total…).

1. Le chauffeur saisit le **motif d'échec**.
2. ASM passe la livraison en **ÉCHEC** et marque la commande « en cours de synchro ».
3. ASM envoie le motif à Odoo et reçoit l'accusé.
4. Le dispatcheur peut ensuite **replanifier** la livraison (nouvelle date) si besoin.

## 5. Annulation

Une livraison est annulée côté ASM (commande annulée, etc.).

1. ASM passe la livraison en **ANNULÉE** (l'historique est conservé).
2. ASM envoie l'annulation du **bon précis** concerné à Odoo — même si la commande
   compte plusieurs livraisons (multi-dépôt, reliquat), c'est bien la bonne qui est annulée.

## 6. Preuve de livraison (POD)

Photo du bon, photo du colis, signature, commentaire, position GPS.

1. Les images sont stockées dans le stockage de fichiers d'ASM (MinIO).
2. La consigne envoyée à Odoo ne contient **que les liens** vers ces images, pas les
   images elles-mêmes (plus léger, plus fiable). L'adaptateur va les chercher et les
   dépose dans Odoo.

## 7. Retour (RMA)

Le client renvoie une marchandise déjà livrée.

1. On ne peut ouvrir un retour que sur une livraison **livrée** ou **partiellement livrée**.
2. On ne peut retourner **que ce qui a été livré** (quantités bornées). Un seul retour
   ouvert à la fois par livraison.
3. Cycle : **Demandé → Approuvé → Reçu → Remis en stock**, avec **Rejeté / Annulé** comme
   sorties (un motif est alors **obligatoire**).
4. À l'étape « Remis en stock », ASM envoie à Odoo un **mouvement de stock inverse**
   (la marchandise revient en stock ; un article **endommagé** part au rebut plutôt qu'en
   stock vendable).
5. Le retour suit le **même principe d'accusé** que les livraisons : tant qu'Odoo n'a
   pas confirmé, il reste « en cours de synchro » ; en cas de refus d'Odoo, il passe en
   **échec** et un responsable est notifié — il ne « disparaît » jamais en silence.

---

## Et si quelque chose se passe mal ?

- **Odoo est en panne / lent.** La consigne reste dans la file et est **réessayée
  automatiquement**, avec des délais de plus en plus longs (jusqu'à plusieurs dizaines
  d'heures). Rien n'est perdu : une fois Odoo revenu, ça part tout seul.
- **Après trop d'échecs.** La commande passe en **Échec de synchro** et un responsable
  est notifié. Il peut relancer la synchro manuellement (« Resynchroniser »).
- **Un accusé se perd en route.** Une **vérification automatique** (toutes les 5 minutes)
  repère les commandes restées « en cours de synchro » trop longtemps (au-delà de
  15 minutes) et relance l'envoi. Aucune commande ne reste bloquée indéfiniment.
- **Un message arrive en double.** Sans effet : un accusé rejoué ne crée pas un second
  reliquat et ne change pas un état déjà final.

---

## Ajouter un autre ERP plus tard

ASM est conçu pour fonctionner avec **un ERP par instance**. Pour brancher un autre ERP
(SAP, Sage…), il suffit d'écrire un nouvel **adaptateur** et de changer **un seul
réglage** (`erp.provider`). Le reste du système (livraisons, retours, file d'attente,
accusés) ne change pas.
