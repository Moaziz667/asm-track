# ADR-028 — L'insertion d'un arrêt suit la fenêtre engagée, jamais une heure calculée

**Statut :** acceptée · **Portée :** DeliveryMicroservice (résolution d'exceptions, réaffectation)

---

## Contexte

Quand le dispatcheur déplace une livraison vers la tournée d'un autre chauffeur, il faut décider
**où** l'arrêt s'intercale dans la séquence existante.

Deux informations pourraient en décider, et elles ne sont pas de même nature :

- **La fenêtre horaire** de chaque arrêt : ce qui a été promis au destinataire, saisi ou validé par
  un humain.
- **L'heure d'arrivée estimée**, recalculée à partir des temps de trajet du moteur d'itinéraires.

L'heure estimée paraît plus précise. Elle l'est même, à l'instant où on la calcule. Le problème est
qu'elle change toute seule : le moteur d'itinéraires ne connaît ni le trafic du jour, ni le client
qui n'ouvre qu'à quatorze heures, ni les vingt minutes perdues à l'arrêt précédent. Un ordre construit
sur elle se réorganise donc à chaque recalcul, sans que personne n'ait rien demandé, et le dispatcheur
retrouve une tournée qu'il n'a pas ordonnée.

---

## Décision

L'insertion s'appuie **uniquement sur les fenêtres horaires de début et de fin**, celles qu'un humain
a engagées. Aucune heure estimée n'entre dans le calcul de position.

Trois règles, dans cet ordre :

1. **Un plancher infranchissable.** Un arrêt ne peut jamais être inséré avant un arrêt déjà terminé,
   qu'il soit livré, partiel ou en échec. Le passé est figé, et c'est la seule contrainte que le
   dispatcheur ne peut pas lever.
2. **Un tri par fenêtre de début**, départagé par la fenêtre de fin la plus proche. C'est la règle du
   délai le plus court d'abord : à heure d'ouverture égale, l'arrêt dont la porte se referme en
   premier passe devant. Un arrêt sans fenêtre est « à tout moment » et ne réclame aucune place.
3. **Un conflit signalé, pas corrigé.** Si la fenêtre ne peut être honorée qu'avant le plancher, ou
   si le créneau retenu chevauche un voisin, le système le dit et nomme l'arrêt en cause.

Quand le dispatcheur choisit lui-même la position, seul le plancher subsiste. **Les chevauchements
sont alors autorisés**, parce qu'ils sont un choix humain assumé et non une erreur de calcul. Ils ne
disparaissent pas pour autant : le moteur d'échéances mesure chaque arrêt contre sa propre fenêtre,
et un chevauchement se manifestera comme un retard.

C'est le point qui donne son sens à la décision. Le système ne prétend pas empêcher une tournée
tendue. Il refuse de la maquiller.

---

## Alternatives écartées

### Insérer selon l'heure d'arrivée estimée
Le comportement initial. Une tournée se réordonnait après un recalcul de trajet, et le dispatcheur
voyait bouger un ordre qu'il avait fixé. Une aide qui défait le travail de celui qu'elle assiste
n'est pas une aide.

### Interdire tout chevauchement de fenêtres
Rigoureux, et impraticable en fin de journée. Le dispatcheur qui insère une livraison urgente dans une
tournée déjà pleine sait qu'il crée une tension ; la lui interdire le pousse à contourner l'outil.
Mieux vaut accepter le geste et le mesurer.

### Réoptimiser toute la tournée à chaque insertion
Cohérent avec le moteur d'optimisation, et brutal : le chauffeur est en route, il a déjà en tête ses
trois prochains arrêts. Réordonner ce qui est devant lui pour gagner quelques minutes lui coûte plus
que ces minutes.

### Laisser le dispatcheur placer l'arrêt sans aucune règle
C'est l'option ouverte, mais elle ne peut pas être la seule : la réaffectation en un clic doit bien
proposer une position. La règle sert de valeur par défaut ; le placement manuel reste disponible.

---

## Conséquences

**Ce qu'on gagne.** Un ordre de passage stable, qui ne change que si quelqu'un le change. Les retards
restent mesurés contre une promesse qu'un humain a faite, ce qui est la condition pour qu'un
indicateur de ponctualité veuille dire quelque chose.

**Ce que ça coûte.** La position proposée ignore les temps de trajet, donc elle n'est pas la plus
courte. Un arrêt inséré au bon endroit du point de vue des horaires peut imposer un détour. Le
dispatcheur voit la carte et peut corriger, mais le système ne l'y aide pas.

**Ce qui reste ouvert.** Les arrêts sans fenêtre horaire ne forcent aucune position et se retrouvent
en fin de séquence. C'est acceptable tant qu'ils sont rares ; une tournée majoritairement sans
fenêtres perdrait tout ordre utile.
