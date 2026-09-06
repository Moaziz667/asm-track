# Certificat TLS du serveur

Ce dossier est **ignoré par Git**, à l'exception de ce fichier : une clé privée ne se versionne
jamais. Les certificats sont donc à régénérer sur chaque serveur, d'où cette note.

Ils ne sont montés que par `docker-compose.prod.yml`. En local, la pile reste en HTTP : le
navigateur traite `localhost` comme une origine sécurisée, donc `crypto.subtle` et la connexion
OIDC fonctionnent sans TLS. Cette faveur ne vaut que pour `localhost` — servie sur une adresse IP,
la page se charge mais la connexion échoue sur *« crypto.subtle is available only in secure
contexts »*. C'est la raison d'être de ce dossier.

## Générer un certificat auto-signé

Depuis `Microservices/`, en remplaçant l'adresse par celle du serveur :

```sh
mkdir -p certs
openssl req -x509 -nodes -days 365 \
  -newkey rsa:2048 \
  -keyout certs/asm-track.key \
  -out    certs/asm-track.crt \
  -subj   "/CN=192.168.10.76" \
  -addext "subjectAltName=IP:192.168.10.76"
```

Le `subjectAltName` n'est pas décoratif : les navigateurs ignorent le `CN` depuis longtemps et
rejettent un certificat dont l'adresse ne figure pas dans cette extension.

Vérification :

```sh
openssl x509 -in certs/asm-track.crt -noout -subject -ext subjectAltName
```

## Poser les droits

nginx tourne sous l'UID 101 dans l'image non privilégiée et ne peut pas lire une clé qui ne lui
appartient pas. Sans cette étape, le conteneur refuse de démarrer sur
`cannot load certificate key … Permission denied` :

```sh
sudo chown 101:101 certs/asm-track.key certs/asm-track.crt
sudo chmod 600 certs/asm-track.key
sudo chmod 644 certs/asm-track.crt
```

## Ce qu'un certificat auto-signé ne résout pas

Le chiffrement fonctionne, mais aucun navigateur ne fait confiance à un certificat qu'il ne peut
rattacher à une autorité connue : Chrome affiche « Non sécurisé ». Pour lever l'avertissement, il
faut soit installer le certificat comme racine de confiance sur chaque poste client, soit — la
seule voie propre en production — un vrai certificat délivré pour un nom de domaine réel.

## Après un changement d'adresse ou de protocole

Keycloak valide les URLs de redirection contre une liste enregistrée dans sa base. Elle n'est pas
relue depuis `keycloak/asm-realm.json`, qui n'est importé qu'au tout premier démarrage sur une base
vide : la mise à jour se fait sur l'instance en service, sinon la connexion échoue sur
*« Paramètre invalide : redirect_uri »*.

```sh
KC=/opt/keycloak/bin/kcadm.sh
ID=$(docker exec keycloak $KC get clients -r asm \
       -q clientId=admin-web --fields id --format csv --noquotes)

docker exec keycloak $KC update clients/$ID -r asm \
  -s 'redirectUris=["https://192.168.10.76/*"]' \
  -s 'webOrigins=["https://192.168.10.76"]'
```

Penser aussi à `PUBLIC_KEYCLOAK_URL` dans le `.env` du serveur, que les services utilisent comme
émetteur attendu des jetons (`AUTH_ISSUER_URL`). En HTTPS elle vaut
`https://192.168.10.76/auth/realms/asm` : une valeur restée en `http://` fait rejeter tous les
jetons, avec une erreur qui parle d'émetteur et non de protocole.
