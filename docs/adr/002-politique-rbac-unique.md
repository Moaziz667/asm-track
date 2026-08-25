# ADR-002 — Une politique d'autorisation déclarative, unique et *fail-closed*

**Statut :** acceptée · **Portée :** ApiGateway, AppBackend, DeliveryMicroservice, DriverService

---

## Contexte

La plateforme expose environ 250 endpoints, répartis sur six services, pour des profils aux droits
très différents : administrateur, dispatcher, responsable, livreur, et des appels entre services.
Les droits fins sont portés par des rôles composites Keycloak de la forme `perm:route:manage`,
`perm:cash:receive`, `perm:report:view`.

La question à laquelle il fallait pouvoir répondre — en soutenance comme en exploitation — est
simple à poser et redoutable à tenir : **« qui a le droit de faire quoi ? »**

Avec l'approche habituelle, la réponse est répartie sur 250 annotations dans 47 fichiers. Personne ne
peut la donner, et personne ne peut vérifier qu'un endroit n'a pas été oublié.

---

## Décision

**Un fichier déclaratif unique**, `rbac-policy.json`, décrit toutes les règles `chemin → permission` :

```json
{ "methods": ["GET"], "pathPrefix": "/api/v1/admin/routes",    "require": { "perm": "perm:route:view" } },
{ "methods": ["*"],   "pathPrefix": "/api/v1/admin/",          "require": { "perm": "perm:dispatch:operate" } }
```

Quatre propriétés en font autre chose qu'un fichier de configuration :

**1. Les règles sont ordonnées, premier match gagnant.** Les cas particuliers précèdent le cas
général, comme dans une table de routage. La règle `/api/v1/admin/` en dernière position est un filet
de sécurité : tout ce qui n'a pas été prévu plus haut exige au minimum `perm:dispatch:operate`.

**2. L'absence de règle vaut refus.** C'est la dernière ligne de l'évaluation :

```java
public static boolean isAuthorized(String path, Set<String> roles, HttpMethod method) {
    for (Rule r : RULES) {
        if (methodMatches(r, method) && pathMatches(r, path)) {
            return evaluate(r.require(), roles);
        }
    }
    return false;   // ← fail-closed
}
```

Un endpoint ajouté et oublié dans la politique est **inaccessible**, pas ouvert. L'erreur se
manifeste par un 403 pendant le développement, jamais par une fuite en production.

**3. Le fichier est répliqué à l'identique dans chaque service**, par le script
`sync-rbac-policy.py`. Les quatre copies portent la même empreinte MD5. Chaque service embarque sa
copie plutôt que d'interroger la gateway à l'exécution : pas d'appel réseau sur le chemin critique,
et pas de point de défaillance unique.

**4. Elle est évaluée deux fois** — à la gateway, puis dans le service. Un appel qui atteindrait un
service en contournant la gateway est évalué avec les mêmes règles. La défense ne dépend pas de la
topologie du réseau.

---

## Alternatives écartées

### `@PreAuthorize` sur chaque méthode
L'approche Spring standard, et de loin la plus répandue. Écartée pour une raison unique mais
suffisante : **elle rend la politique illisible dans son ensemble.** Répondre à « qui peut annuler
une tournée ? » demande de parcourir le code. Vérifier qu'aucun endpoint n'a été oublié est
impossible sans outillage. Et la règle par défaut, quand l'annotation manque, est *l'accès autorisé*
— l'inverse de ce qu'on veut.

### Tout centraliser dans la gateway
Une seule évaluation, plus simple. Mais elle fait reposer la sécurité sur le fait que personne
n'atteint jamais un service directement — une propriété du déploiement, pas du code. En
développement, où les services sont joignables sur leurs ports, elle est déjà fausse.

### Un serveur de politique (OPA, Keycloak Authorization Services)
Le bon outil à une autre échelle. Ici, il ajouterait un composant à déployer, à surveiller et à
maintenir disponible, pour évaluer quelques dizaines de règles. Le coût d'exploitation dépasse le
bénéfice.

---

## Conséquences

**Ce qu'on gagne.** La politique tient en 40 lignes lisibles par un non-développeur. Un audit se fait
en lisant un fichier. Un oubli échoue de manière sûre. Les quatre services ne peuvent pas diverger,
puisqu'ils partagent l'octet près le même fichier.

**Ce que ça coûte.** Les règles sont fondées sur des **préfixes de chemin**, donc l'URL porte une
partie du sens métier : renommer `/api/v1/admin/routes` sans toucher à la politique change les
droits. C'est la contrepartie assumée de la lisibilité — et la raison pour laquelle les chaînes
`perm:*` doivent rester alignées avec les composites Keycloak (`keycloak/rbac-roles.json`).

Le fichier doit aussi être resynchronisé après chaque modification. Le script le fait ; l'oublier
laisserait un service avec une politique périmée. Une vérification en CI serait le prolongement
naturel de cette décision.
