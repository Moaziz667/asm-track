# ADR-029 — Les arrêts de chargement sont recalculés, jamais déplacés à la main

**Statut :** acceptée · **Portée :** DeliveryMicroservice (tournées, dispatch, transfert d'arrêts)

---

## Contexte

Une tournée comporte deux natures d'arrêts. Les **livraisons**, chez des clients, et les
**chargements**, aux dépôts où le chauffeur prend les colis. Les seconds n'existent que pour servir
les premiers : un chargement à Sousse n'a de raison d'être que parce qu'une livraison de cette
tournée y prend sa marchandise.

Cette dépendance est facile à respecter à la construction, et facile à casser ensuite. Dès qu'une
livraison change de tournée, les deux tournées deviennent fausses au même instant :

- **La tournée d'origine** garde un chargement devenu orphelin, dont plus aucune livraison n'a
  besoin. Il reste `PENDING`, et il empêche la clôture de la tournée avec un message qui ne veut plus
  rien dire pour le chauffeur : « arrêts encore actifs — 1 en attente ».
- **La tournée d'accueil** n'a pas le chargement dont sa nouvelle livraison dépend. Le chauffeur est
  censé livrer un colis qu'aucun arrêt ne lui fait prendre.

Le déplacement d'un seul arrêt en casse donc deux autres, dans deux tournées différentes, dont une
que le dispatcheur n'a pas ouverte.

---

## Décision

Les arrêts de chargement ne sont **pas des données que l'on modifie**, mais un résultat que l'on
recalcule. Après tout déplacement de livraison, un réconciliateur repasse sur les **deux** tournées
et rétablit l'invariant : une tournée porte exactement les chargements dont ses livraisons ont
besoin, ni plus, ni moins.

Le réconciliateur relit les arrêts depuis la base plutôt que de raisonner sur l'état en mémoire, de
sorte qu'il observe la situation d'après le déplacement et non celle d'avant.

Un cas particulier a demandé un traitement explicite. Quand la tournée d'accueil vient d'être créée,
elle n'a pas encore de dépôt de rattachement. Sans indication, elle en déduirait un chargement chez
elle-même, ce qui n'a pas de sens. Le dépôt de la première livraison déplacée sert donc à
l'initialiser, avant que la réconciliation ne s'exécute.

Enfin, une tournée d'origine vidée de ses arrêts n'est pas clôturée automatiquement tant que son
chauffeur détient encore physiquement un colis en attente de remise. La clôturer figerait un rapport
de fin de journée annonçant une tournée terminée pendant que la marchandise est dans le camion.

---

## Alternatives écartées

### Déplacer le chargement en même temps que la livraison
Le réflexe évident, et faux dès qu'une tournée compte deux livraisons tirant du même dépôt. Déplacer
le chargement avec la première prive la seconde de sa marchandise. Le raisonnement correct porte sur
l'ensemble des livraisons restantes, pas sur celle qu'on déplace.

### Laisser le dispatcheur gérer les chargements à la main
Cohérent avec l'idée qu'il maîtrise sa tournée, et intenable : la dépendance entre une livraison et
son dépôt n'est pas affichée arrêt par arrêt. On lui demanderait de tenir de tête un invariant que
l'écran ne lui montre pas, à chaque déplacement, sur deux tournées.

### Ne créer les chargements qu'au moment de valider la tournée
Repousse le problème sans le résoudre : une tournée déjà validée, donc déjà partie, reste modifiable
en cours de journée. C'est précisément là que le déplacement se produit.

### Tolérer les chargements orphelins et les ignorer à la clôture
Le correctif le moins cher, et il déplace la saleté ailleurs. Un arrêt qui existe mais qu'on apprend
à ne pas regarder finit par être regardé par un autre bout de code, un rapport ou un décompte
d'avancement.

---

## Conséquences

**Ce qu'on gagne.** L'invariant tient sans que personne n'ait à y penser. Un dispatcheur déplace une
livraison ; les chargements des deux tournées se réajustent, et la tournée vidée peut se clôturer.

**Ce que ça coûte.** Une lecture supplémentaire des arrêts des deux tournées à chaque déplacement, et
un ordre d'exécution qui doit être respecté : le dépôt de rattachement doit être initialisé avant la
réconciliation, faute de quoi celle-ci produit un chargement absurde. C'est une contrainte de
séquence, la forme de couplage la plus facile à casser par inadvertance lors d'une refonte.

**Ce qui reste ouvert.** La réconciliation s'exécute sur les chemins de déplacement connus, la
réaffectation et le transfert d'arrêts. Un futur chemin qui déplacerait une livraison sans l'appeler
recréerait exactement le défaut d'origine. Rien dans le code ne l'en empêche aujourd'hui.
