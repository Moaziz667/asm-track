# ops/backup — Sauvegarde & restauration

Tout ce qui concerne la sauvegarde de la plateforme est ici : les scripts, la procédure, la
planification et le plan de mise en œuvre.

```
ops/backup/
├── backup.sh          sauvegarde complète (bases + volumes)
├── restore.sh         restauration : un client, une base, ou un volume
├── crontab.example    planification quotidienne
├── PLAN.md            plan de mise en œuvre + scénario de démonstration
└── README.md          ce fichier
```

---

## En deux minutes

```bash
# Sauvegarder maintenant
./backup.sh

# Restaurer un seul client (les autres continuent de tourner)
./restore.sh ../../backups/20260730-023000 tenant company_54ed4906... --yes

# Répéter une restauration sans toucher à la production
./restore.sh ../../backups/20260730-023000 tenant company_54ed4906... --into essai_restauration --yes
```

---

## Ce qui est sauvegardé

| Composant | Contenu | Format |
|---|---|---|
| `delivery_db` | commandes, livraisons, tournées, journal ERP — **la base métier** | `pg_dump -Fc` |
| `app_db` | comptes, tenants, paramètres | `pg_dump -Fc` |
| `driver_db` | chauffeurs, positions, file hors-ligne | `pg_dump -Fc` |
| `keycloak_db` | identités, rôles, realms | `pg_dump -Fc` |
| volume MinIO | **photos et signatures de preuve de livraison** | `tar.gz` |
| volume `erp-adapter` | mappings de champs ERP, cache d'idempotence | `tar.gz` |
| `delivery_base` | copie physique de `delivery_db`, pour la reprise à la minute | `pg_basebackup` |
| `wal-archive` | les journaux de transactions de `delivery_db` | `tar.gz` |

**Volontairement exclus :** RabbitMQ (transitoire — l'outbox en base est la source de vérité,
les messages en vol se reconstruisent après restauration) et OSRM (re-téléchargeable).

Le format *custom* (`-Fc`) n'est pas un détail : c'est lui qui permet de restaurer **un seul
schéma client**. Un dump SQL classique ne saurait faire que du tout-ou-rien.

### Pourquoi `delivery_db` est sauvegardée deux fois

Ce n'est pas une redondance, ce sont deux mécanismes qui ne se mélangent pas :

- `pg_dump` produit des **instructions SQL**. Lisible, filtrable par schéma, donc c'est lui qui
  permet de restaurer un seul client. Mais il fige un instant : la nuit précédente.
- Les **journaux de transactions** décrivent des modifications de pages **physiques**. On ne peut
  pas les rejouer par-dessus un `pg_dump` — les deux ne parlent pas de la même chose. Ils
  exigent une copie physique de départ, c'est le rôle de `pg_basebackup`.

D'où le choix au moment de l'incident :

| Situation | Quoi utiliser | Perte |
|---|---|---|
| Un client a cassé ses données | `delivery_db.dump`, mode `tenant` | jusqu'à 24 h |
| Le serveur est mort, il faut tout et le plus récent possible | `delivery_base` + journaux | ~5 min |

### Pourquoi MinIO compte autant que les bases

Une base restaurée sans MinIO donne des livraisons marquées « livré » sans aucune preuve
derrière. Juridiquement, c'est une livraison non prouvée. Les deux se restaurent ensemble.

---

## Sauvegarder

```bash
./backup.sh                    # vers <dépôt>/backups/
./backup.sh /mnt/sauvegardes   # ailleurs
```

| Variable | Défaut | Rôle |
|---|---|---|
| `BACKUP_DIR` | `<dépôt>/backups` | où écrire |
| `BACKUP_KEEP` | `14` | nombre de sauvegardes conservées |
| `BACKUP_REMOTE` | *(vide)* | cible `rclone`, ex. `b2:asm-backups` |

Le script écrit un dossier daté :

```
backups/20260730-023000/
├── delivery_db.dump      2,5 Mo   verified
├── app_db.dump           117 Ko   verified
├── driver_db.dump        120 Ko   verified
├── keycloak_db.dump      237 Ko   verified
├── minio_data.tar.gz      34 Mo   verified
├── erp-adapter.tar.gz    120 Ko   verified
└── MANIFEST
```

`verified` n'est pas décoratif : après chaque archive, le script la **relit**
(`pg_restore -l`, `gzip -t`). Un dump tronqué se détecte le jour où il est pris, pas le jour
où on en a besoin.

**Aucun mot de passe n'est nécessaire.** `pg_dump` tourne via `docker exec` (l'image Postgres
officielle fait confiance aux connexions par socket local) et les volumes sont lus avec
`--volumes-from`. Un script de sauvegarde qui réclame des secrets finit avec ces secrets dans
une crontab.

### Copie hors-machine

Une sauvegarde posée sur le disque qu'elle protège défend contre un `DELETE` malheureux, pas
contre une panne disque ni un rançongiciel — qui chiffre les sauvegardes avec le reste.

```bash
rclone config                     # configure la cible, une fois — les identifiants restent chez toi
export BACKUP_REMOTE=b2:asm-backups
```

Tant que `BACKUP_REMOTE` est vide, le script le dit à chaque exécution plutôt que de laisser
croire que la sauvegarde est à l'abri.

---

## Restaurer

### 1. Un seul client — le cas courant

```bash
./restore.sh <dossier> tenant company_54ed4906... --yes
```

L'architecture est *un schéma par client*. Quand un client casse ses données, les autres ne
sont ni touchés ni arrêtés. **Aucun service n'a besoin d'être stoppé.**

### 2. Une base entière — disque mort, migration ratée

```bash
docker compose stop delivery-service    # d'abord
./restore.sh <dossier> database delivery --yes
docker compose start delivery-service
```

La base est supprimée puis recréée. Le script évince les connexions ouvertes lui-même : une
base à moitié restaurée est pire qu'une restauration refusée.

### 3. Les preuves de livraison

```bash
./restore.sh <dossier> volume minio --yes
```

Le conteneur est arrêté pendant l'échange puis redémarré — remplacer des fichiers sous un
processus vivant donne un service debout qui sert la moitié des données, ce qui ressemble à un
succès sans en être un.

### 4. Répéter sans risque

```bash
./restore.sh <dossier> tenant company_54ed4906... --into essai_restauration --yes
```

Restaure le schéma dans une base jetable. La production n'est pas touchée. C'est la forme à
utiliser pour l'exercice de reprise et pour la démonstration.

### 5. Revenir à une minute précise (reprise dans le temps)

Pour l'incident large — serveur perdu, corruption datée — où l'on veut le plus récent état
possible plutôt que la nuit précédente. Procédure manuelle, volontairement : elle remplace le
répertoire de données de PostgreSQL, ce n'est pas une opération à déclencher d'une commande.

```bash
docker compose stop delivery-service
docker stop postgres-delivery

# 1. Repartir de la copie physique
docker run --rm --volumes-from postgres-delivery -i alpine:3 \
  sh -c 'rm -rf /var/lib/postgresql/data/* && tar xzf - -C /var/lib/postgresql/data' \
  < backups/<date>/delivery_base.tar.gz

# 2. Remettre les journaux en place
docker run --rm --volumes-from postgres-delivery -i alpine:3 \
  sh -c 'tar xzf - -C /wal-archive' < backups/<date>/wal-archive.tar.gz

# 3. Dire à PostgreSQL jusqu'où rejouer, puis le laisser travailler
docker run --rm --volumes-from postgres-delivery alpine:3 sh -c '
  printf "restore_command = '"'"'cp /wal-archive/%%f \"%%p\"'"'"'\n" \
    >> /var/lib/postgresql/data/postgresql.auto.conf
  printf "recovery_target_time = '"'"'2026-07-31 14:55:00+01'"'"'\n" \
    >> /var/lib/postgresql/data/postgresql.auto.conf
  touch /var/lib/postgresql/data/recovery.signal'

docker start postgres-delivery      # rejoue les journaux jusqu'à l'heure demandée
```

Suivre la reprise dans `docker logs postgres-delivery`, puis sortir du mode restauration avec
`SELECT pg_wal_replay_resume()` une fois l'état vérifié.

> **Non encore éprouvé.** Le mécanisme est en place et l'archivage tourne
> (`pg_stat_archiver` : 0 échec), mais cette procédure n'a pas encore été jouée de bout en
> bout. Tant que ce n'est pas fait, l'engagement tenable reste **RPO 24 h**, pas 5 minutes.
> C'est l'exercice décrit dans [PLAN.md](PLAN.md), partie B.

> Rien ne s'exécute sans `--yes`. Une restauration écrase des données vivantes.

**Après une restauration, comptez les lignes avant d'annoncer que ça a marché.** Un
`pg_restore` qui rend la main sans erreur n'est pas une preuve.

---

## Planifier

```bash
sudo ./install-cron.sh            # installe la tâche quotidienne (02h30)
sudo ./install-cron.sh --show     # ce qui est installé
sudo ./install-cron.sh --remove   # la retirer
```

Le script est idempotent : le relancer laisse **une** entrée, pas deux. Il ne modifie que la
ligne qu'il a posée (repérée par une étiquette), le reste du crontab n'est pas touché. Il pose
aussi la rotation du journal — sinon une année d'exécutions nocturnes remplit doucement le
disque sur lequel on écrit les sauvegardes.

Pour une pose à la main, la ligne est dans [crontab.example](crontab.example).

Pas de conteneur de sauvegarde dans le `docker-compose` : il lui faudrait le socket Docker,
c'est-à-dire un accès root déguisé sur toute la machine, pour économiser une ligne de crontab.

> ⚠️ **Windows n'a pas de cron.** En local, la sauvegarde ne part que lancée à la main. C'est
> voulu : des données de développement n'ont rien à protéger. La planification est une affaire
> de serveur.

### Surveiller

Le vrai risque n'est pas « la sauvegarde a échoué », c'est « la sauvegarde a échoué il y a
trois semaines et personne ne l'a vu ». Chaque exécution écrit `backups/last-backup.json` —
**y compris en cas d'échec** :

```json
{
  "status": "ok",
  "finishedAt": "2026-07-30T02:31:14+01:00",
  "directory": "20260730-023000",
  "detail": "37M",
  "offsite": false
}
```

Ce fichier est destiné à la page **System Health**, pour qu'une sauvegarde muette devienne
visible à l'écran.

---

## Première installation sur un serveur

Dans l'ordre, une seule fois :

```bash
# 1. Le dossier de destination doit exister AVANT que la stack démarre : Docker monte
#    ../backups en lecture seule dans delivery-service et, s'il est absent, en crée un
#    vide appartenant à root. Le service démarre alors sans jamais voir de sauvegarde.
mkdir -p /opt/asm/backups

# 2. Vérifier la place. Compter ~15 Mo par sauvegarde aujourd'hui, 14 conservées, et
#    prévoir large : le volume grandit avec les photos de preuve de livraison.
df -h /opt/asm

# 3. Une sauvegarde manuelle d'abord — on n'automatise pas quelque chose qu'on n'a pas
#    vu réussir.
cd /opt/asm/ops/backup && ./backup.sh

# 4. Puis seulement, la planification.
sudo ./install-cron.sh
```

Le lendemain, deux vérifications qui prennent dix secondes : `tail /var/log/asm-backup.log`,
et la ligne « Dernière sauvegarde » sur la page System Health.

**Les sauvegardes du serveur et celles d'un poste de développement sont deux mondes séparés.**
Le cron installé ici protège les données de ce serveur, rien d'autre.

## Engagements

| | Valeur | État |
|---|---|---|
| **RPO** — données perdues au pire | 24 h aujourd'hui ; ~5 min sur `delivery_db` une fois la reprise dans le temps éprouvée | mécanisme en place, exercice à jouer |
| **RTO** — temps de remise en service | restauration d'un client mesurée à **2 s** (76 commandes, 77 livraisons) | mesuré |
| **Rétention** | 14 jours en local | en place |
| **Copie hors-machine** | `BACKUP_REMOTE` non renseignée | à activer |

Détail du raisonnement et étapes restantes : [PLAN.md](PLAN.md).
