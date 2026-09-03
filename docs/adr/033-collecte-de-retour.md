# ADR-033 — La collecte de retour est une livraison, pas un objet nouveau

**Statut :** acceptée · **Portée :** DeliveryMicroservice (RMA, tournées, preuve) · application chauffeur · suivi public

---

## Contexte

Un destinataire demande le retour d'un article. Une fois la demande approuvée, quelqu'un doit aller
chercher le colis chez lui et le ramener au dépôt.

Ce mouvement ressemble beaucoup à une livraison lue à l'envers. Il a une adresse, une fenêtre
horaire, un chauffeur, des articles, une preuve à rapporter. Il entre dans une tournée, il se
planifie, il peut échouer parce que le client est absent.

La tentation est de créer un objet dédié — une « collecte » — parce que le sens métier est inverse.
Le coût de ce choix se voit tout de suite si on le déroule : il faudrait redévelopper la
planification, l'affectation à un chauffeur, l'ordonnancement des arrêts, le suivi temps réel, le
moteur d'échéances, l'écran mobile et la preuve. Sept mécanismes réécrits pour un mouvement qui
partage tout avec celui qui existe, sauf la direction.

---

## Décision

Un retour est une **livraison de type `RETURN_PICKUP`**, du client vers le dépôt, réutilisant la
commande d'origine.

```sql
ALTER TABLE deliveries
    ADD COLUMN kind            VARCHAR(20) NOT NULL DEFAULT 'FORWARD',
    ADD COLUMN rma_id          UUID,
    ADD COLUMN return_depot_id UUID;
```

Trois colonnes, et tout le reste est hérité. La collecte traverse la même machine à états, entre dans
les mêmes tournées, s'affecte de la même façon, se prouve sur le même écran mobile.

Là où le sens inverse impose une différence, elle est explicite et locale :

- **Pas de bon de livraison à photographier.** Le document n'existe pas pour un mouvement inverse :
  c'est l'ERP qui produit le bon, et il n'en produit pas pour un retour. La preuve d'une collecte est
  donc la seule photo du colis récupéré. C'est la seule exception à la règle qui veut qu'une preuve
  porte le document signé.
- **Tout ou rien.** Une collecte n'admet pas de livraison partielle. Un écart entre ce que le client
  rend et ce qu'il avait annoncé se constate à l'inspection au dépôt, par quelqu'un qui peut ouvrir
  le carton, et non par un chauffeur sur un pas de porte.
- **Les lignes viennent de la demande de retour, pas de la commande.** La collecte partage la
  commande d'origine, qui est une transaction close et synchronisée. Ce qu'il faut récupérer est
  décrit par la demande, et c'est elle qui fait foi sur chaque écran qui liste des articles.
- **Aucun mouvement de stock sortant.** Une collecte réutilise la commande d'origine, donc elle ne
  doit surtout pas repousser vers l'ERP la validation d'expédition que la livraison aller a déjà
  faite.
- **Sa propre référence.** La demande reçoit un numéro lisible qui lui appartient, `RET-00001`, tiré
  d'une séquence PostgreSQL. Partager la référence de la commande aurait rendu impossible de
  désigner un retour au téléphone sans ambiguïté.

Un échec de collecte ferme la demande de retour, ce qui libère la garde du « un seul retour ouvert à
la fois » : sans cela, un client absent une fois ne pourrait plus jamais redemander de retour.

---

## Alternatives écartées

### Une entité « collecte » distincte
La solution que le vocabulaire métier suggère, et la plus coûteuse. Elle duplique sept mécanismes
éprouvés pour en obtenir sept variantes à maintenir en parallèle. Chaque correction faite sur la
planification aurait ensuite dû être refaite sur la collecte, ou oubliée.

### Réutiliser la livraison aller en inversant son état
Séduisant parce que c'est le même colis. Faux parce que ce n'est pas le même événement : la livraison
aller est un compte rendu de ce qui s'est passé un jour donné. La rouvrir pour y écrire un retour
détruit la trace du passage initial, et le litige porte précisément sur cette trace.

### Un simple drapeau sur la livraison
Ce qui a été fait, à ceci près que `kind` est une énumération et non un booléen. La différence
compte : un troisième sens de circulation, un échange par exemple, ne demanderait alors qu'une
valeur de plus.

### Traiter le retour hors du système de tournées
Défendable si les retours étaient rares et groupés en fin de semaine. Ils ne le sont pas, et un
chauffeur qui livre dans une rue est le mieux placé pour y reprendre un colis. Les sortir des
tournées, c'est refaire le trajet une seconde fois.

---

## Conséquences

**Ce qu'on gagne.** Un retour bénéficie sans une ligne de code supplémentaire de l'optimisation d'ordre
de passage, du suivi en direct, du calcul des délais, de la réaffectation et du transfert de garde.
Un chauffeur n'apprend rien de nouveau : c'est son écran habituel, avec un libellé inversé.

**Ce que ça coûte.** La table `deliveries` porte désormais des lignes de deux natures, et tout code
qui la lit doit savoir laquelle il regarde. Une dizaine d'endroits testent explicitement le type —
les lignes à afficher, la preuve exigée, la synchronisation ERP, le rapport de tournée. Chacun est un
endroit où un oubli produirait un comportement inversé plutôt qu'une erreur visible. C'est le prix de
la réutilisation, et il est réel.

**Ce qui reste ouvert.** La distinction entre un retour physiquement revenu au dépôt et un retour
jugé revendable puis réintégré au stock relève du cycle de la demande, pas de la collecte. Un article
rendu abîmé s'arrête au premier moment. Ce découpage tient, mais il repose sur une inspection
humaine au dépôt que le système enregistre sans pouvoir la garantir.

Voir [ADR-031](031-transfert-de-garde.md) pour l'autre mouvement latéral du terrain.
