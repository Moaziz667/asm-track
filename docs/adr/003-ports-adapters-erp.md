# ADR-003 — Intégration ERP par Ports & Adapters

**Statut :** acceptée · **Portée :** ErpAdapterService

---

## Contexte

ASM Track ne remplace pas l'ERP de ses clients : il s'y branche. Un transporteur qui adopte la
plateforme a déjà un système où vivent ses commandes, ses articles et ses bons de livraison, et il
n'a aucune intention d'en changer.

Trois faits rendent cette intégration difficile :

1. **Les clients n'ont pas le même ERP.** Odoo domine le marché tunisien des PME, mais pas seul.
2. **Un même ERP change entre versions.** Entre Odoo 16 et 19, `stock.move.line.qty_done` devient
   `quantity`, et `create_returns` devient `action_create_returns`. Un client en 16 et un client en
   19 sont, pour le connecteur, deux systèmes différents.
3. **Certains clients n'ont pas d'ERP du tout**, et doivent quand même pouvoir utiliser la
   plateforme.

Si ces trois variations remontent dans le code métier, chaque règle de livraison finit entourée de
conditions sur le fournisseur et sa version.

---

## Décision

**Quatre interfaces** décrivent ce dont le métier a besoin, sans nommer aucun ERP :

| Port | Ce qu'il exprime |
|---|---|
| `ErpSyncPort` | pousser un résultat terrain — livré, partiel, échoué, annulé, retourné |
| `ErpLookupPort` | lire des commandes en attente, des clients, des dépôts |
| `ErpOrderPort` | créer et résoudre des références de commande |
| `ErpChangePort` | détecter qu'une commande a changé côté ERP |

**Trois familles d'adaptateurs** les implémentent :

```
adapter/odoo/      25 classes, 5 108 lignes  — JSON-RPC
adapter/erpnext/    4 classes, 1 520 lignes  — REST Frappe
config/Noop*        3 classes,    47 lignes  — client sans ERP
```

L'écart de volume entre les deux connecteurs n'est pas un déséquilibre : Odoo expose des assistants
de confirmation à piloter et quatre versions à couvrir, là où ERPNext offre une API REST uniforme.
C'est précisément ce que le port masque au métier.

Le routage se fait par **nom de bean** : `ErpProviderRouter` normalise `OdooSyncAdapter` en `"odoo"`,
`ErpNextLookupAdapter` en `"erpnext"`, et sélectionne l'implémentation selon le fournisseur configuré
pour le client. Ajouter un ERP ne modifie aucun code existant.

### Les deux compléments qui font tenir la décision

**Le patron Objet Nul.** Un client sans ERP est servi par `NoopSyncAdapter`, dont les méthodes
répondent « succès » sans rien faire. L'alternative — un `if (erp == null)` dispersé — aurait mis la
question de l'ERP dans chaque chemin métier, ce que le port existe précisément pour éviter.

**Le moteur de capacités.** Les différences entre versions ne sont pas codées en dur mais déclarées
dans `odoo-capabilities.json` :

```json
"DONE_QUANTITY": {
  "model": "stock.move.line",
  "type": "FIELD",
  "candidates": ["quantity", "qty_done"]
}
```

Le connecteur demande à l'instance quels champs et méthodes elle expose réellement, puis choisit. Il
ne teste jamais un numéro de version — il teste la présence. Une version intermédiaire non prévue
fonctionne donc sans modification.

---

## Alternatives écartées

### Appeler Odoo directement depuis le service de livraison
Le chemin le plus court, et celui qui coûte le plus tard : le premier client sous un autre ERP impose
de retrouver et réécrire chaque appel dispersé dans le métier. La frontière n'existerait qu'au moment
où il serait trop tard pour la tracer.

### Une couche d'abstraction sans seconde implémentation
Écrire les ports « au cas où », avec Odoo seul derrière. C'est la forme la plus courante de
sur-conception : une interface qui n'a jamais été confrontée à un second cas est presque toujours
modelée sur le premier, et ne survit pas au second. **Cette décision n'a été validée que par ERPNext**
— dont l'ajout n'a demandé aucune modification des ports.

### Un ETL ou une synchronisation par fichiers
Découplage maximal, mais incompatible avec le besoin : le suivi de livraison est temps réel, et un
client qui valide un bon de livraison veut le voir dans son ERP tout de suite.

---

## Conséquences

**Ce qu'on gagne.** Le métier ignore quel ERP tourne en face. Ajouter un fournisseur consiste à créer
des beans nommés, sans toucher au reste. Les différences de version sont des données, pas du code.

**Ce que ça coûte.** Les ports expriment le plus petit dénominateur commun : une fonctionnalité qu'un
seul ERP sait faire n'a pas de place naturelle. Et une abstraction ajoute une couche à traverser pour
comprendre un flux — coût réel, accepté parce que deux implémentations le justifient.

**Ce qui reste ouvert.** Un troisième adaptateur, `dux`, existe à l'état d'ébauche : ses méthodes
journalisent un avertissement et renvoient un échec. Il est conservé comme trace d'une intégration
envisagée, mais **un client configuré sur ce fournisseur ne se synchroniserait pas**. Il devrait être
supprimé ou terminé.

**Comment la décision est vérifiée.** La suite d'intégration s'exécute contre **deux instances Odoo
simultanées, 16 et 19**, démarrées par la CI. Les deux extrémités de la plage supportée étant
couvertes, les versions intermédiaires le sont par construction.
