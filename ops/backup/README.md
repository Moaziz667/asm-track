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

**Volontairement exclus :** RabbitMQ (transitoire — l'outbox en base est la source de vérité,
les messages en vol se reconstruisent après restauration) et OSRM (re-téléchargeable).

Le format *custom* (`-Fc`) n'est pas un détail : c'est lui qui permet de restaurer **un seul
schéma client**. Un dump SQL classique ne saurait faire que du tout-ou-rien.

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

> Rien ne s'exécute sans `--yes`. Une restauration écrase des données vivantes.

**Après une restauration, comptez les lignes avant d'annoncer que ça a marché.** Un
`pg_restore` qui rend la main sans erreur n'est pas une preuve.

---

## Planifier

Voir [crontab.example](crontab.example). Une ligne, quotidienne à 02h30.

Pas de conteneur de sauvegarde dans le `docker-compose` : il lui faudrait le socket Docker,
c'est-à-dire un accès root déguisé sur toute la machine, pour économiser une ligne de crontab.

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

## Engagements

| | Valeur |
|---|---|
| **RPO** — données perdues au pire | ~5 min sur `delivery_db` (journaux WAL), 24 h ailleurs |
| **RTO** — temps de remise en service | mesuré lors de l'exercice de reprise |
| **Rétention** | 14 jours en local |

Détail du raisonnement et étapes restantes : [PLAN.md](PLAN.md).
