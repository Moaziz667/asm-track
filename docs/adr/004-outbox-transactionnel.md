# ADR-004 — Synchronisation ERP par outbox transactionnel

**Statut :** acceptée · **Portée :** DeliveryMicroservice → RabbitMQ → ErpAdapterService

---

## Contexte

Quand un livreur valide une livraison sur son téléphone, deux choses doivent arriver : la livraison
passe à `DELIVERED` dans ASM Track, et le bon de livraison est validé dans l'ERP du client.

Ce sont **deux systèmes distincts**, et rien ne garantit qu'ils soient disponibles au même instant.
L'ERP d'un client peut être en maintenance, injoignable, ou simplement lent. Le livreur, lui, est
devant une porte et attend que son écran réponde.

Deux échecs sont inacceptables, et ce sont les deux plus faciles à écrire :

- **La livraison est enregistrée, l'ERP ne l'apprend jamais.** Le client facture un bon de livraison
  qui reste ouvert dans son système. Personne ne s'en aperçoit avant l'inventaire.
- **La livraison échoue parce que l'ERP est en panne.** Le livreur ne peut pas travailler à cause
  d'un système sur lequel ni lui ni nous n'avons la main.

---

## Décision

Le fait métier et son intention de synchronisation sont écrits **dans la même transaction de base de
données** :

```
BEGIN
  UPDATE deliveries  SET status = 'DELIVERED' …
  INSERT INTO outbox_event (event_type, payload, status='PENDING') …
COMMIT
```

Les deux réussissent ou aucun des deux. Il devient impossible d'avoir une livraison validée sans
événement en attente — cette garantie vient de PostgreSQL, pas d'un `try/catch`.

Et elle n'est pas laissée à la vigilance de l'appelant :

```java
@Transactional(propagation = Propagation.MANDATORY)
public void enqueue(String type, Object payload) { … }
```

`MANDATORY` fait échouer l'appel s'il n'existe pas déjà une transaction en cours. Écrire dans
l'outbox **hors** d'une transaction métier n'est donc pas une erreur qu'on peut commettre : le
conteneur la refuse. La propriété centrale de ce patron est ainsi vérifiée par le framework, pas
seulement documentée.

Un processus séparé, `OutboxProcessor`, lit les événements `PENDING` et les publie vers RabbitMQ, où
`ErpAdapterService` les consomme et parle à l'ERP.

### La politique de reprise

```java
if (newRetryCount <= 15) {
    event.setStatus("PENDING");
    long backoffSeconds = (long) Math.min(Math.pow(2, newRetryCount) * 5, 3600 * 4);
    event.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds));
} else {
    event.setStatus("FAILED");
}
```

**15 tentatives, délai doublant à chaque essai** (`2^n × 5 s`), plafonné à 4 heures par tentative.
Les premiers essais sont rapprochés — une coupure réseau de quelques secondes se rattrape sans que
personne ne le remarque — puis l'espacement grandit pour ne pas marteler un système hors service.

**Ce que cette politique couvre réellement : 21,7 heures.** La rédaction de cet ADR a corrigé une
affirmation fausse qui figurait dans le code (« 45+ hours, fully protecting against weekend
outages ») : la somme n'avait jamais été calculée. La queue domine — les essais 12 à 15, tous au
plafond de 4 heures, apportent 16 de ces heures, quand les onze premiers réunis en ajoutent moins
de 6.

21,7 heures couvrent confortablement une panne nocturne et entièrement une journée de travail. Elles
**ne couvrent pas** une panne du vendredi soir résolue le lundi : il faudrait 25 tentatives pour
atteindre 60 heures. Le compromis est assumé — un événement en échec depuis une journée relève plus
souvent d'une configuration cassée que d'une indisponibilité, et la file morte le garde visible et
rejouable plutôt que perdu.

Au-delà, l'événement passe `FAILED` et rejoint la file morte, consultable et rejouable depuis le
back-office (`/api/v1/admin/dlq`). Un échec définitif reste donc **visible et réparable**, au lieu de
disparaître dans un journal.

---

## Alternatives écartées

### Appeler l'ERP directement, dans la transaction
Le plus simple à écrire, et faux pour une raison structurelle : **on ne peut pas inclure un appel
réseau dans une transaction de base de données.** Si l'appel réussit et que le commit échoue, l'ERP a
enregistré une livraison qu'ASM Track ignore. L'inverse est tout aussi possible. C'est le problème
classique de la double écriture, et il n'a pas de solution à ce niveau.

### Publier vers RabbitMQ juste après le commit
Presque correct, et c'est ce qui le rend dangereux : la fenêtre entre le `COMMIT` et le `publish` est
courte mais réelle. Un arrêt du service à cet instant précis perd l'événement, sans trace. Le défaut
ne se manifeste que sous charge ou lors d'un incident — exactement quand on ne peut pas l'analyser.

### Un journal de transactions (CDC, Debezium)
La solution robuste à grande échelle. Elle demande un composant supplémentaire, une configuration de
réplication PostgreSQL et une exploitation dédiée — hors de proportion pour le volume visé, où une
table et une tâche périodique suffisent.

### Aucune reprise : échouer et alerter
Défendable si les pannes d'ERP étaient rares et brèves. Elles ne le sont pas : les instances des
clients sont souvent auto-hébergées, avec des fenêtres de maintenance non annoncées.

---

## Conséquences

**Ce qu'on gagne.** Aucune livraison ne peut être enregistrée sans que sa synchronisation soit au
moins tentée. Un ERP indisponible ralentit la synchronisation sans jamais bloquer le terrain : le
livreur valide, l'événement attend. Et l'état de la synchronisation est **une table qu'on peut
interroger**, pas une supposition.

**Ce que ça coûte.** La cohérence est *à terme*, pas immédiate : entre la validation et son
apparition dans l'ERP, il s'écoule un délai — invisible en fonctionnement normal, potentiellement
long pendant une panne. L'interface doit donc le refléter, et c'est ce que fait `erpSyncStatus` sur
la commande (`PENDING_SYNC` → `SYNCED` / `SYNC_FAILED`), plutôt que de laisser croire à une écriture
instantanée.

S'y ajoutent une table à purger et un processus de plus à surveiller.

**Ce qui reste ouvert.** La purge des événements `PROCESSED` n'est pas automatisée : la table croît.
