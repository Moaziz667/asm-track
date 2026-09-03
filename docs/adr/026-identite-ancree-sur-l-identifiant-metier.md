# ADR-026 — L'identifiant métier ancre l'identité, Keycloak est interrogé à la volée

**Statut :** acceptée · **Portée :** AppBackend (administration Keycloak) · DriverService · passerelle

---

## Contexte

Un utilisateur d'ASM Track existe des deux côtés : une ligne en base applicative, qui porte son
métier, et un compte Keycloak, qui porte son authentification. Il faut pouvoir passer de l'un à
l'autre, dans les deux sens et à tout moment — pour changer un mot de passe, désactiver un compte,
poser une photo de profil, ou remonter d'un jeton reçu jusqu'à la personne qu'il désigne.

La façon habituelle de relier deux systèmes est de stocker l'identifiant de l'un chez l'autre : une
colonne `keycloak_user_id` dans la base applicative. Elle a un défaut connu, et un défaut propre à ce
projet.

Le défaut connu est qu'elle crée **une seconde source de vérité à maintenir**. Un compte recréé à la
main dans la console Keycloak, un `realm` réimporté, une restauration partielle, et la colonne pointe
vers un compte qui n'existe plus. La panne ne se manifeste qu'à la première opération d'administration,
souvent des semaines plus tard.

Le défaut propre au projet est que **les comptes n'ont pas tous la même forme**. Les administrateurs
initiaux sont créés par l'amorçage du `realm` et portent leur adresse électronique comme nom
d'utilisateur. Les comptes créés ensuite par l'application, chauffeurs compris, portent un UUID. Une
recherche par nom d'utilisateur fonctionne donc pour une moitié de la population et échoue pour
l'autre.

---

## Décision

**L'identifiant métier est l'ancre, des deux côtés.** Rien n'est stocké dans le sens
Keycloak → application.

À la création d'un compte, l'identifiant applicatif est écrit **deux fois** dans Keycloak :

```java
userPayload.put("username", appUserId);              // stable, immuable
attributes.put("app_user_id", List.of(appUserId));   // pose sur tous les comptes
```

Le nom d'utilisateur est l'identifiant métier, et il ne change jamais. L'adresse électronique, qui
change, est reléguée au rang d'attribut ordinaire — l'inverse du réglage habituel, et c'est
délibéré : ce qui identifie ne doit pas être ce que l'utilisateur peut modifier.

L'attribut `app_user_id` fait doublon avec le nom d'utilisateur pour les comptes créés par
l'application. Il existe pour les autres : posé sur **tous** les comptes, y compris les
administrateurs initiaux dont le nom d'utilisateur est une adresse, il donne une clé de recherche
unique qui vaut pour toute la population.

La résolution se fait donc à la volée, avec un repli :

1. Chercher par l'attribut `app_user_id`, en correspondance exacte.
2. À défaut, et seulement si une adresse est connue, chercher par adresse.

Toute modification d'un compte fusionne dans la carte d'attributs existante au lieu de la remplacer,
faute de quoi une simple mise à jour de photo effacerait la clé de recherche.

---

## Alternatives écartées

### Stocker `keycloak_user_id` en base applicative
L'option évidente. Elle échange une requête contre une donnée à maintenir cohérente avec un système
extérieur qu'un administrateur peut modifier hors de l'application. Le jour où elle diverge, rien ne
le signale.

### Chercher par nom d'utilisateur
Ce que faisait la première version. Elle fonctionne jusqu'à ce qu'on la lance sur un administrateur
initial, dont le nom d'utilisateur est une adresse et non un UUID. Le défaut est resté invisible tant
que les tests ne portaient que sur des comptes créés par l'application.

### Chercher par adresse électronique
Utilisable comme repli, pas comme clé. Une adresse change, peut être partagée dans une petite
structure, et peut être absente. C'est exactement ce qu'un identifiant ne doit pas être.

### Synchroniser les deux systèmes par événements
Robuste et disproportionné. Il faudrait un flux, une file, une reprise sur erreur et une réconciliation
pour maintenir une donnée que Keycloak sait rendre en une requête.

---

## Conséquences

**Ce qu'on gagne.** Aucune divergence possible : il n'y a rien à faire diverger. Un compte recréé à la
main reste retrouvable dès lors qu'il porte le bon attribut, et l'application survit à un réimport de
`realm`.

**Ce que ça coûte.** Une requête réseau vers Keycloak avant chaque opération d'administration, là où
une colonne aurait suffi. Le coût est réel mais borné : ces opérations sont rares et jamais sur le
chemin d'une requête chauffeur.

Plus gênant, l'attribut `picture` doit être déclaré dans la configuration du profil utilisateur du
`realm` pour être accepté. Une dépendance à un fichier de configuration, invisible depuis le code, et
qui se manifeste par un échec silencieux si elle manque.

**Ce qui reste ouvert.** Le repli par adresse peut, dans une structure où deux comptes partagent une
adresse, désigner le mauvais. Le cas ne s'est pas présenté, et la parade serait de retirer le repli
une fois tous les comptes historiques pourvus de leur attribut.
