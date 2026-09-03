# ADR-031 — Transfert de garde par état latéral `AWAITING_HANDOFF`

**Statut :** acceptée · **Portée :** DeliveryMicroservice (dispatch, handoff) · application chauffeur

---

## Contexte

Un dispatcheur réaffecte une livraison en cours de journée. Deux situations que rien ne distingue à
l'écran, et qui n'ont pourtant aucun rapport :

- **Le colis est encore au dépôt.** Personne ne le touche. Changer de chauffeur est une écriture,
  rien de plus.
- **Le colis est déjà dans le camion du premier chauffeur.** Là, le système peut écrire ce qu'il
  veut : tant que les deux hommes ne se sont pas croisés, le colis reste dans le premier véhicule.

La première version traitait les deux cas de la même façon : la livraison revenait à `SCHEDULED` sur
la tournée du nouveau chauffeur. Le résultat est un **fantôme**. Le système désigne le chauffeur B
comme responsable pendant que le chauffeur A tient physiquement le colis. Personne ne s'en aperçoit,
parce que rien n'est incohérent en apparence — jusqu'au litige, où l'on découvre que l'enregistrement
et la réalité divergeaient depuis le matin.

Le problème n'est pas l'affectation. C'est que **la garde d'un objet physique ne se transfère pas par
une écriture en base**.

---

## Décision

Un état **latéral** dédié, `AWAITING_HANDOFF`.

```
PICKED_UP / IN_TRANSIT  →  AWAITING_HANDOFF  →  PICKED_UP   (à la confirmation)
                                             →  PICKED_UP   (au sender, si expiration)
```

Latéral, et c'est le mot juste : il ne fait ni avancer ni reculer le cycle de vie, il le **suspend**.
La livraison n'est plus en cours chez A et pas encore en cours chez B. Elle est dans le seul état qui
décrive la vérité du terrain : quelqu'un attend que quelqu'un d'autre lui remette un colis.

La sortie de cet état est un geste physique, pas une décision de bureau. Le chauffeur qui cède
affiche un **code à usage unique**, le chauffeur qui reçoit le scanne. Le code vaut deux heures
(`handoff.token.ttl-minutes`, 120) et supporte cinq tentatives au plus
(`handoff.token.max-attempts`) — deux heures parce qu'une passation sur le terrain se cale sur des
trajets, pas sur un minuteur, et cinq tentatives parce qu'un code court se devine.

Trois compléments rendent la décision tenable en exploitation :

- **Les arrêts de tournée n'héritent pas de cet état.** `route_stops` garde son propre jeu de
  statuts, sans `AWAITING_HANDOFF` : un arrêt transféré redevient simplement `PENDING` sur la tournée
  d'accueil. La garde est une propriété du colis, pas de la ligne d'un planning.
- **La tournée d'origine ne se clôture pas toute seule** tant qu'une remise reste à confirmer. La
  fermer figerait un rapport de fin de journée annonçant une tournée terminée pendant que le colis
  est encore dans le camion.
- **Une remise qui traîne est escaladée.** Une alerte part vers le bureau et vers les deux chauffeurs
  après quinze minutes sans confirmation, une seule fois ; au bout de deux heures la remise est
  annulée d'office, le colis revient au chauffeur qui le tient encore et son arrêt retourne sur la
  tournée de celui-ci. Ce second seuil est **calé sur la durée de vie du code** : à soixante minutes,
  sa valeur initiale, le transfert mourait pendant que le QR affichait encore un compte à rebours
  valide, et le cédant regardait un code que plus personne ne pouvait confirmer. Les deux seuils sont des réglages
  **par entreprise**, la configuration ne fournissant qu'un repli : une ville où les transferts se
  font à pied dans un souk et une autre où les chauffeurs se croisent sur un périphérique ne méritent
  pas la même patience, et c'est un arbitrage d'exploitant, pas de redéploiement. Porter
  l'annulation à zéro la désactive, auquel cas une remise peut rester ouverte indéfiniment — c'est un
  choix que l'entreprise assume.

---

## Alternatives écartées

### Revenir à `SCHEDULED` sur la tournée d'accueil
C'est ce que faisait la première version, et c'est le fantôme décrit plus haut. Le défaut n'est pas
qu'elle soit fausse tout de suite : c'est qu'elle est fausse **silencieusement**, pendant des heures,
et qu'on ne l'apprend que le jour où quelqu'un cherche un colis.

### Réaffecter immédiatement, sans confirmation
Défendable si l'on considère que la décision du dispatcheur *est* le fait. Elle ne l'est pas : ce que
le système enregistrerait alors, c'est une intention. Or la question à laquelle un registre de garde
doit répondre n'est pas « qui devait avoir le colis » mais « qui l'avait ».

### Un objet « remise » vivant à côté du cycle de vie de la livraison
Techniquement plus propre, et faux en pratique. Le statut de la livraison est ce que lisent tous les
écrans, le moteur d'échéances et la chronologie. Une garde consignée ailleurs serait une seconde
vérité, invisible de tout ce qui compte, et les deux finiraient par diverger.

### Un simple drapeau booléen sur la livraison
Moins coûteux qu'un état, et insuffisant pour la raison même qui motive cet ADR. Un drapeau posé sur
une livraison `IN_TRANSIT` la laisse `IN_TRANSIT` pour tout ce qui la lit : les écrans, la
chronologie et le moteur d'échéances continueraient de la compter comme roulant vers le client,
c'est-à-dire exactement le fantôme qu'on cherche à supprimer. Il faudrait alors apprendre le drapeau
à chacun de ces lecteurs, un par un, quand un état le leur dit sans qu'on ait rien à leur demander.

---

## Conséquences

**Ce qu'on gagne.** La garde d'un colis n'est jamais ambiguë : à tout instant, une seule personne en
répond, et le passage de l'un à l'autre est daté, localisé et confirmé par les deux parties. Le
moteur d'échéances cesse d'imputer à un chauffeur un retard sur un colis qu'il n'a pas encore reçu.

**Ce que ça coûte.** Un état de plus dans une énumération que cinq services lisent, et deux
contraintes `CHECK` à élargir — celle de `deliveries` (V22) et celle de `delivery_status_history`
(V23), toutes deux antérieures à la décision et qui rejetaient l'écriture avec un `23514`. Le coût
réel de cet ADR s'est mesuré là : la décision était juste, sa première implémentation plantait en
base.

Un colis peut aussi rester bloqué entre deux mains, ce qui n'arrivait pas avant. C'est la raison
d'être de l'annulation automatique.

**Ce qui reste ouvert.** Tant qu'une remise est en attente, la tournée d'origine reste en cours. Un
chauffeur qui finit sa journée sur une passation jamais confirmée garde donc une tournée ouverte
jusqu'à l'expiration. Le délai d'annulation borne le problème sans le supprimer, et une entreprise
qui choisit de le désactiver le rouvre entièrement.

Voir [ADR-033](033-collecte-de-retour.md) pour l'autre mouvement qui emprunte la même tournée et le
même écran.
