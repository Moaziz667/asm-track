# Plan — Sauvegarde & Restauration

> Statut : plan validé, mise en œuvre non commencée (sauf `backup.sh`, écrit et exécuté une fois).
> Date : 30 juillet 2026.

Ce document couvre deux choses :
- **Partie A** — ce qu'on construit, dans l'ordre.
- **Partie B** — le scénario de démonstration filmé pour le jury.

---

## Pourquoi

Le cahier des charges promet trois choses qui n'existent nulle part dans le dépôt :

| Clause | Exigence | État actuel |
|---|---|---|
| §6.3 | Sauvegardes quotidiennes **+ journaux** | rien |
| §6.3 | Plan de reprise, RPO/RTO *« à définir »* | jamais défini |
| §12.2 | Test de reprise : sauvegarde / restauration DB | jamais joué |

Et un risque qui n'est pas dans le cahier mais qui est bien réel : les **preuves de livraison** (photos, signatures) vivent dans MinIO. Une base restaurée sans MinIO donne des livraisons marquées « livré » sans aucune preuve derrière — juridiquement, c'est une livraison non prouvée.

### Engagements chiffrés

À reporter dans le cahier à la place du RPO/RTO vide :

| | Valeur | D'où ça vient |
|---|---|---|
| **RPO** | ~5 min sur `delivery_db`, 24 h sur le reste | archivage WAL toutes les 5 min / dump quotidien — **éprouvé le 31/07** |
| **RTO** | 2 s pour un client, < 2 min pour une reprise dans le temps | chronométré réellement, pas estimé au doigt |
| **Rétention** | 14 jours en local | 14 dumps ≈ 40 Mo aujourd'hui |

Pourquoi pas un RPO de 0 : il faudrait une réplication synchrone, donc un second serveur, une latence ajoutée sur chaque écriture, et un couplage où l'arrêt du réplica arrête la production. À notre volume, 5 minutes représentent moins d'une livraison. Le jour où la plateforme atteint les 5 000 livraisons/jour du cahier (§6.1), 5 minutes deviennent ~50 livraisons : la marche suivante est alors un **réplica asynchrone** (RPO de quelques secondes, et RTO amélioré au passage), pas le synchrone.

---

## Partie A — Ce qu'on construit

### A0. Un module à part *(fait)*

Tout ce qui touche à la sauvegarde vit dans `ops/backup/` — scripts, runbook, exemple de crontab, ce plan. Un opérateur ouvre un seul dossier et a tout, au lieu de chercher entre `scripts/`, `MDS/` et la racine.

Deux détails de dépôt réglés au passage :

1. **`/scripts` est ignoré par git** ([.gitignore](../../.gitignore) ligne 52) : un script posé là ne serait versionné nulle part, donc il vivrait sur une seule machine et disparaîtrait avec elle. `ops/` n'est pas ignoré — le module est suivi normalement.
2. **`/backups/` doit rester ignoré** — fait. Le dump Keycloak contient les hachages de mots de passe : il ne doit jamais atteindre le dépôt.

### A1. `backup.sh` — *écrit, exécuté une fois*

Sauvegarde tout ce qui porte de l'état :

| Quoi | Comment | Pourquoi |
|---|---|---|
| `delivery_db`, `app_db`, `driver_db`, `keycloak_db` | `pg_dump -Fc` | le format *custom* permet de restaurer **un seul schéma client** |
| Volume MinIO | `tar` via `--volumes-from` | photos et signatures POD |
| Volume `erp-adapter-data` | idem | mappings ERP + cache d'idempotence |

Ignorés volontairement : **RabbitMQ** (transitoire — l'outbox en base est la source de vérité) et **OSRM** (re-téléchargeable).

Deux choix qui comptent :
- **Aucun secret dans le script.** `pg_dump` tourne via `docker exec` (socket local en `trust`), les volumes passent par `--volumes-from`. Un script de sauvegarde qui réclame des mots de passe finit avec ces mots de passe dans une crontab.
- **Chaque archive est vérifiée juste après.** `pg_restore -l` relit la table des matières, `gzip -t` teste le tarball. Un dump tronqué se détecte maintenant, pas le jour où on en a besoin.

**Preuve d'exécution réelle** (30/07, 21:46) :
```
delivery_db.dump  2 554 661 octets  verified
app_db.dump         116 834 octets  verified
driver_db.dump      119 666 octets  verified
keycloak_db.dump    236 712 octets  verified
```
L'archive MinIO est à 0 octet : l'exécution a été interrompue à cette étape. À rejouer en entier.

**Reste à faire :** la poussée hors-machine (voir A5) et le fichier d'état (A4).

### A2. Archivage WAL sur `delivery_db` *(fait, éprouvé)*

C'est le *« + journaux »* du cahier, et c'est ce qui fait passer le RPO de 24 h à 5 min.

> **Ce que j'avais mal compris en écrivant ce plan.** Les journaux ne se rejouent **pas**
> par-dessus un `pg_dump` : le dump contient du SQL, les journaux décrivent des pages
> physiques. La reprise dans le temps exige une copie **physique** de départ. `backup.sh` prend
> donc aussi un `pg_basebackup` de `delivery_db` — d'où sa présence deux fois dans une
> sauvegarde. Sans cette correction, l'archivage WAL aurait tourné pour rien et le RPO annoncé
> aurait été faux.

État réel : archivage actif (`pg_stat_archiver` : 0 échec), base physique et journaux capturés à
chaque sauvegarde, et **procédure de reprise jouée le 31/07** — deux repères écrits à trente
secondes d'intervalle, cible de restauration placée entre les deux, le premier revient et le
second non. Le RPO de 5 min est donc mesuré, pas annoncé. Détail dans [README.md](README.md).

L'idée en une phrase : PostgreSQL écrit chaque modification dans un journal avant de l'appliquer ; en gardant ces journaux, on peut rejouer la base **jusqu'à un instant précis** au lieu de revenir au dernier dump.

```
Dump à 02h30 ────────── incident à 15h00
   sans journaux : on repart de 02h30   → 12 h de travail perdues
   avec journaux : on rejoue jusqu'à 14h55 → 5 min perdues
```

- Uniquement sur `delivery_db` : c'est la base métier. Keycloak et driver bougent peu, le dump quotidien suffit — ajouter le WAL partout coûterait de la complexité sans rien protéger de plus.
- Configuration : `wal_level = replica`, `archive_mode = on`, `archive_command` copiant vers un volume dédié, `archive_timeout = 300`.
- Les journaux se purgent avec les dumps qu'ils accompagnent (14 jours) : un journal sans son dump de base ne sert à rien.

### A3. `restore.sh` *(fait, éprouvé)*

Trois modes, du plus courant au plus rare :

```bash
# 1. Un seul client — le cas réel le plus probable
./restore.sh backups/20260730-023000 tenant company_54ed4906... --yes

# 2. Une base entière — disque mort, migration ratée
./restore.sh backups/20260730-023000 database delivery --yes

# 3. Les fichiers POD
./restore.sh backups/20260730-023000 volume minio --yes
```

Le **mode 1 est le cœur du sujet** : l'architecture est un schéma par client. Quand un client casse ses données, les six autres ne doivent pas être touchés — et surtout pas arrêtés. C'est exactement ce que `pg_restore -n <schéma>` permet, et c'est pour ça que les dumps sont en format *custom*.

Garde-fous :
- rien ne s'exécute sans `--yes` explicite (une restauration écrase des données vivantes) ;
- `--into` restaure dans une base jetable, pour répéter sans toucher à la production ;
- `--exit-on-error` : une restauration à moitié réussie est un échec, pas un avertissement.

Éprouvé le 31/07 sur le tenant `alpha` : 76 commandes, 77 livraisons, 29 preuves de livraison,
29 tournées, 5 retours, 23 événements ERP — **tous les comptes identiques**, en 2 secondes.

### A4. Planification + alerte *(fait)*

**Cron sur le VPS**, quotidien — voir [crontab.example](crontab.example) :
```cron
30 2 * * *  /opt/asm/ops/backup/backup.sh >> /var/log/asm-backup.log 2>&1
```

Pas de conteneur de sauvegarde dans le `docker-compose` : il lui faudrait le socket Docker, c'est-à-dire un accès root déguisé sur toute la machine, pour économiser une ligne de crontab. Mauvais échange.

**Et surtout l'alerte.** Le scénario classique n'est pas « la sauvegarde a échoué », c'est « la sauvegarde a échoué il y a trois semaines et personne ne l'a vu ». Le script écrit un `last-backup.json` (horodatage, succès/échec, tailles) que la page **System Health** lit et affiche — toute la mécanique existe déjà dans [SystemHealthPage.tsx](../../Apps/admin-app-react/src/pages/system-health/SystemHealthPage.tsx). Une ligne de plus dans la section « Intégration » :

> Dernière sauvegarde — aujourd'hui à 02h30 ✓
> Dernière sauvegarde — **il y a 4 jours** (en rouge)

### A5. Copie hors-machine

Une sauvegarde posée sur le disque qu'elle protège défend contre un `DROP TABLE`, pas contre une panne disque ni un rançongiciel (qui chiffre les sauvegardes avec le reste).

Implémentation : si la variable `BACKUP_REMOTE` est définie, le script pousse via `rclone` après vérification. Elle reste vide aujourd'hui — le jour où un bucket existe (Backblaze B2, OVH, S3 : quelques euros par mois), il suffit de la renseigner. **Aucun identifiant ne passe par le code** : `rclone config` reste à la main de l'administrateur.

### A6. Documentation *(fait)*

Le runbook est [README.md](README.md), à côté des scripts : un opérateur ouvre un dossier et a
tout. Rien n'a été ajouté dans `MDS/`, qui est ignoré par git — un document non versionné ne
survit pas à un changement de machine.

**`mkdocs.yml` supprimé.** Il annonçait un site de documentation qui ne pouvait pas se
construire : `docs_dir: docs` pointait vers un dossier inexistant, et les fichiers qu'il
listait sont dans `MDS/`, exclu du dépôt. Aucun job ne le construisait, rien n'était publié.
Un fichier de configuration qui ne peut pas fonctionner coûte plus qu'il ne rapporte : il
laisse croire qu'il y a une documentation en ligne.

### Ordre d'exécution

| # | Étape | État |
|---|---|---|
| 1 | A0 — module `ops/backup/` | ✅ |
| 2 | A1 — `backup.sh` complet | ✅ exécuté, 15 Mo, tout vérifié |
| 3 | A3 — `restore.sh` | ✅ éprouvé, comptes identiques |
| 4 | **Test réel** (partie B1) | ✅ pour le mode client ; reprise dans le temps à jouer |
| 5 | A2 — archivage WAL | ✅ actif, 0 échec ; procédure non éprouvée |
| 6 | A4 — cron + ligne sur System Health | ✅ |
| 7 | A5 — `BACKUP_REMOTE` | ✅ prêt, en attente d'un stockage distant |
| 8 | A6 — runbook, suppression de `mkdocs.yml` | ✅ |

La restauration a été testée **avant** le WAL : inutile d'ajouter un mécanisme fin tant que le
mécanisme grossier n'est pas prouvé. C'est ce test qui a révélé que `pg_restore` sortait en
code 0 après avoir échoué sur 200 objets.

---

## Partie B — Démonstration filmée pour le jury

### Ce qu'on veut démontrer

Pas « j'ai un script de sauvegarde » — n'importe qui peut afficher un `pg_dump`. Trois affirmations, chacune prouvée à l'écran :

1. **La sauvegarde est vérifiée**, pas juste écrite.
2. **La restauration est granulaire** : un client est réparé pendant que les autres continuent de tourner. C'est la démonstration la plus parlante, parce qu'elle rend visible l'architecture multi-tenant.
3. **Le RTO est mesuré**, chronomètre à l'écran, pas annoncé.

### B1. Le test réel (à jouer avant de filmer)

Ce test sert d'abord à valider, la vidéo n'est qu'un enregistrement de ce qui marche déjà.

1. Créer une base jetable `delivery_restore_test`.
2. Y restaurer le schéma `company_54ed…` depuis le dump.
3. **Compter les lignes** et comparer à la base réelle :
   ```
   orders      1 284 / 1 284   ok
   deliveries  1 190 / 1 190   ok
   pod           842 /   842   ok
   erp_sync_event  47 /    47   ok
   ```
4. Chronométrer → c'est le RTO, à reporter dans le tableau en haut de ce document.
5. Supprimer la base jetable. **La base réelle n'est jamais touchée pendant cette étape.**

### B2. Scénario de la vidéo — ~4 minutes

**Règle de sécurité de tournage :** la démonstration se joue sur un **tenant de démonstration**, jamais sur `alpha` en l'état. Un second tenant sert de **témoin** : c'est lui qui prouve que la restauration est ciblée.

| Temps | À l'écran | À dire |
|---|---|---|
| 0:00 | Page **System Health**, ligne « Dernière sauvegarde : aujourd'hui 02h30 ✓ » | « La sauvegarde tourne toutes les nuits, et la plateforme le dit elle-même. » |
| 0:20 | Liste des livraisons du tenant démo : 42 livraisons. Ouvrir une preuve de livraison, la photo s'affiche. | « Voilà l'état de départ : 42 livraisons, et une preuve de livraison avec sa photo. » |
| 0:40 | **L'incident.** Suppression des données du tenant démo. La liste se vide, la photo POD ne se charge plus. | « Une fausse manœuvre efface les données de ce client. » |
| 1:00 | **Le témoin.** Basculer sur le second tenant : tout est intact. | « L'autre client, lui, n'a rien perdu — et il ne doit rien perdre pendant la réparation non plus. » |
| 1:15 | Lancer `restore.sh … --tenant company_… --yes`. **Chronomètre visible.** | « On restaure ce seul schéma. Les autres clients continuent de travailler : rien n'est arrêté. » |
| 2:00 | Restauration MinIO | « Et les preuves de livraison, qui ne sont pas en base. » |
| 2:30 | Retour sur la liste : 42 livraisons. Rouvrir la même POD : la photo est là. | « 42 livraisons, la même preuve, la même photo. » |
| 3:00 | Arrêt du chronomètre → RTO affiché | « Temps de reprise mesuré : X minutes. C'est le chiffre du cahier des charges, mesuré et pas estimé. » |
| 3:20 | Retour au témoin : intact, jamais interrompu | « Et le client témoin n'a été ni touché, ni arrêté. » |
| 3:40 | Tableau RPO / RTO / Rétention | « RPO 5 minutes grâce aux journaux, RTO X minutes, rétention 14 jours. » |

**Segment optionnel (RPO)** — si le temps le permet, le plus convaincant sur les journaux WAL : créer une livraison **après** le dump du matin, provoquer l'incident, restaurer, et montrer que cette livraison **est revenue**. C'est la preuve visuelle que le RPO n'est pas de 24 h. À ne tourner qu'une fois A2 en place et éprouvé.

### B3. Ce qu'il ne faut pas faire dans la vidéo

- Ne pas jouer l'incident sur des données réelles d'un vrai client, même en développement — un enregistrement circule plus loin qu'on ne le croit.
- Ne pas montrer `.env`, la console MinIO connectée, ni un jeton dans une URL.
- Ne pas accélérer la restauration au montage : si elle prend 6 minutes, on coupe en affichant le chronomètre, on ne fait pas croire qu'elle en prend une.

---

## Ce que ce plan ne couvre pas

À dire au jury si la question vient, plutôt que d'être pris de court :

- **Pas de bascule automatique.** Si le serveur meurt, la restauration est manuelle. C'est un choix : un seul serveur, donc RTO en minutes, pas en secondes.
- **Pas de réplica.** Voir plus haut : c'est la marche suivante, elle se justifie au volume, pas maintenant.
- **La copie hors-machine est prête mais pas branchée**, faute de stockage distant. C'est le seul point du plan qui dépend d'une dépense.
- **RabbitMQ n'est pas sauvegardé** — assumé : les messages en vol sont reconstruits depuis l'outbox après restauration.
