# Limites connues

Cette page existe parce qu'une documentation qui n'énonce que des réussites n'a pas été écrite
honnêtement. Chaque limite est réelle, vérifiée, et accompagnée de son impact.

---

## 🔴 Ce qui bloquerait une mise en production

### Aucune sauvegarde planifiée

`ops/backup/install-cron.sh` **n'a jamais été exécuté**.

L'archivage WAL de `postgres-delivery` est actif, ce qui borne la perte à cinq minutes — mais il n'y
a **aucun dump périodique** ni aucune restauration testée. Un archivage sans sauvegarde de base est
une demi-mesure : on peut rejouer des journaux, pas repartir de zéro.

**Impact.** Perte totale possible sur incident disque.

### TLS absent

La gateway écoute en clair sur le port 80. Un reverse proxy avec certificat reste à mettre en place,
et `KC_HOSTNAME_STRICT` est à `false`.

**Impact.** Jetons transmis en clair — inacceptable hors réseau local.

---

## 🟠 Ce qui limiterait l'exploitation

### L'adaptateur ERP ne peut pas être répliqué

H2 en mode fichier n'accepte **qu'un seul écrivain**. Ce service ne peut donc pas tourner en deux
instances.

**Impact.** Point de contention unique sur la synchronisation ERP. Acceptable au volume visé ;
bloquant à l'échelle.

**Piste.** Migrer ce magasin vers PostgreSQL — les migrations Flyway existent déjà, ce serait un
changement de dialecte.

### La reprise ne couvre pas un week-end

15 tentatives, **21,7 heures**. Une panne du vendredi soir résolue le lundi dépasse la fenêtre :
l'événement part en file morte et attend un rejeu manuel.

**Piste.** 25 tentatives couvriraient 60 h — au prix d'événements qui mettent 2,5 jours à mourir.

### Pas de vérification de la synchronisation RBAC en CI

`rbac-policy.json` est répliqué dans cinq services par un script. **Rien ne vérifie** que les
copies sont identiques au moment du build.

**Impact.** Une resynchronisation oubliée ferait diverger la gateway et un service sur les règles
d'autorisation.

**Piste.** Un job de CI comparant les empreintes — quelques lignes, le meilleur rapport de cette page.

---

## 🟡 Dettes assumées

### L'adaptateur DUX est une ébauche

Trois classes dont toutes les méthodes journalisent un avertissement et renvoient un échec. Le
fournisseur `'DUX'` apparaît pourtant dans le type TypeScript du front et dans les trois fichiers de
traduction.

**Impact.** Un client configuré sur ce fournisseur **ne se synchroniserait pas**, sans erreur visible
côté utilisateur.

**Décision.** Conservé délibérément comme trace d'une intégration envisagée. Devrait être supprimé
ou terminé.

### Couverture de tests inégale

| Service | Tests / classes |
|---|---|
| ErpAdapterService | 198 / 110 ✅ |
| DeliveryMicroservice | 188 / 343 ~ |
| AppBackend | 31 / 53 ⚠️ |
| DriverService | 19 / 78 ⚠️ |
| Application Flutter | **0** ⚠️ |

L'application livreur n'a aucun test automatisé. Elle est validée manuellement et par le script de
bout en bout `ops/cod/e2e.sh`.

### La disponibilité du livreur n'est pas initialisée

Un livreur est créé `OFFLINE`, et `NearestDriverService` exclut les `OFFLINE`. Tant qu'aucun livreur
ne s'est mis en ligne depuis l'application, la réaffectation ne propose personne.

Ce n'est pas un défaut de conception — un livreur hors service ne doit pas être proposé — mais un
piège sur un environnement de démonstration.

### Le géorepérage est écrit mais neutralisé

`DriverDeliveryService.validateGeofence()` refuse une preuve de livraison déposée à plus de 4 km du
point de livraison — distance de Haversine, coordonnées prises au moment de la photo. Le contrôle
est **désactivé** : son corps est commenté, pour que la démonstration reste possible depuis un poste
qui n'est pas sur le terrain.

**Impact.** Rien n'empêche aujourd'hui de prouver une livraison depuis le dépôt. Le GPS est bien
enregistré sur la preuve — la position est donc *constatable a posteriori*, simplement pas opposable
au moment du dépôt.

**Piste.** Décommenter avant la mise en service. Le seuil de 4 km est délibérément large : une
adresse mal géocodée est fréquente, et refuser une livraison réelle coûte plus cher que d'accepter
une preuve à corriger.

### Aucun test de charge

Les choix évitent des goulots connus, sans preuve chiffrée de tenue en charge.

---

## 🔵 Améliorations naturelles

| Piste | Bénéfice |
|---|---|
| Purge des `outbox_event` traités | la table croît indéfiniment |
| Déprovisionnement automatique d'un client | la création est automatisée, pas la suppression |
| Tests de bout en bout en CI | le script existe, il est manuel |
| Analyse de vulnérabilités des dépendances | absente du pipeline |
| Alertes sur la file morte | la table est consultable, rien ne prévient |

---

## Ce que cette page dit du projet

!!! quote "Le critère qui compte"
    Aucune de ces limites n'est **structurelle**. Elles se corrigent toutes sans retoucher
    l'architecture — ce qui est précisément le signe que l'architecture tient.

    Les points rouges relèvent de l'exploitation (sauvegardes, TLS), pas de la conception. Les points
    orange sont des choix d'échelle assumés pour la cible visée.
