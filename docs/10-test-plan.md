# 10 — Plan de Tests & Critères d'Acceptation

---

## 10.1 Tests Fonctionnels

### TF-01 — Création de livraison depuis commande ERP

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier qu'une commande Odoo importée crée une livraison correctement |
| **Préconditions** | Odoo accessible, commande `state=sale` non encore importée |
| **Étapes** | 1. `GET /api/admin/erp/pending-orders` → vérifier la commande apparaît<br>2. `POST /api/admin/erp/import-order/{erpOrderId}`<br>3. Vérifier le retour `{orderId, deliveryId}`<br>4. `GET /api/admin/deliveries/{id}` → vérifier `status=UNSCHEDULED`, `source=ODOO`, `erpOrderId` correct |
| **Résultat attendu** | Livraison créée, statut `UNSCHEDULED`, event WebSocket `delivery.created` reçu |
| **Critère d'acceptation** | Import idempotent : ré-importer la même commande → même `deliveryId`, pas de doublon |

---

### TF-02 — Affectation tournée et réception mobile

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier qu'une tournée validée est reçue par le chauffeur sur l'app mobile |
| **Préconditions** | Livraison `UNSCHEDULED` existante, chauffeur connecté avec token FCM valide |
| **Étapes** | 1. Admin : créer tournée `POST /api/admin/routes`<br>2. Ajouter arrêt `POST /api/admin/routes/{id}/stops`<br>3. Valider `PUT /api/admin/routes/{id}/validate`<br>4. App mobile : `GET /api/driver/routes/today` → vérifier tournée présente<br>5. Vérifier notification FCM reçue |
| **Résultat attendu** | Tournée visible côté driver, notification push reçue, statut `VALIDATED` |
| **Critère d'acceptation** | Le chauffeur voit la tournée sans rafraîchissement manuel |

---

### TF-03 — Mode offline : collecte événements puis sync

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier que les actions effectuées sans connexion sont rejouées après reconnexion |
| **Préconditions** | Livraison `SCHEDULED` affectée au chauffeur de test |
| **Étapes** | 1. Couper le réseau sur le téléphone (mode avion)<br>2. App : appuyer **Ramasser le colis** → confirmer<br>3. App : appuyer **Démarrer le transit** → confirmer<br>4. Réactiver le réseau<br>5. Attendre 5 secondes<br>6. Admin : vérifier `GET /api/admin/deliveries/{id}` → `status=IN_TRANSIT` |
| **Résultat attendu** | Les deux actions (pickup + transit) synchronisées dans l'ordre, avec les horodatages de l'action réelle (pas de l'heure de reconnexion) |
| **Critère d'acceptation** | `picked_up_at` < `in_transit_at`, tous deux antérieurs à l'heure de reconnexion |

---

### TF-04 — POD consultable côté Web après sync

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier que les photos POD uploadées sont visibles dans l'admin |
| **Préconditions** | Livraison `IN_TRANSIT` |
| **Étapes** | 1. App mobile : valider la livraison avec deux photos<br>2. Admin : ouvrir la page de détail de la livraison<br>3. Vérifier la galerie POD (photo bon de livraison + photo colis)<br>4. Cliquer sur une photo → vérifier l'ouverture (URL MinIO signée) |
| **Résultat attendu** | Photos visibles dans les 5 secondes après soumission, URL MinIO accessible |
| **Critère d'acceptation** | Disponibilité POD < 5 secondes après synchronisation (voir §10.3) |

---

### TF-05 — Statut final remonté dans l'ERP

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier que la validation d'une livraison déclenche la mise à jour du stock dans Odoo |
| **Préconditions** | Livraison `IN_TRANSIT` liée à une commande Odoo (`erpOrderId` non null) |
| **Étapes** | 1. App : soumettre le POD → livraison passe `DELIVERED`<br>2. Attendre max 20 secondes (OutboxProcessor)<br>3. Odoo : vérifier `stock.picking` → statut `done`<br>4. Base : `SELECT status FROM outbox_event WHERE event_type='ERP_SYNC_STOCK'` → `PROCESSED` |
| **Résultat attendu** | `outbox_event.status = PROCESSED`, picking Odoo validé |
| **Critère d'acceptation** | Sync ERP < 30 secondes après livraison confirmée |

---

### TF-06 — Livraison partielle et backorder

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier la création d'un backorder pour les articles non livrés |
| **Préconditions** | Livraison `IN_TRANSIT` avec commande multi-articles |
| **Étapes** | 1. App : livraison partielle avec quantités réduites<br>2. Admin : `GET /api/admin/deliveries/{id}` → `status=PARTIALLY_DELIVERED`<br>3. Admin : `POST /api/admin/deliveries/{id}/create-backorder`<br>4. Vérifier nouvelle livraison `UNSCHEDULED` avec `parentOrderId` |
| **Résultat attendu** | Backorder créé, Odoo notifié via outbox `ERP_SYNC_STOCK` avec `isPartial=true` |

---

### TF-07 — Transfert QR entre chauffeurs

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier la chaîne de transfert physique de colis via QR |
| **Préconditions** | Livraison `PICKED_UP` par Chauffeur A, réaffectée à Chauffeur B |
| **Étapes** | 1. Chauffeur A : `GET /api/driver/deliveries/{id}/handoff-token`<br>2. Chauffeur B : `POST /api/driver/deliveries/{id}/handoff` avec le token<br>3. Vérifier livraison `PICKED_UP` côté Chauffeur B<br>4. Admin : event WebSocket `delivery.handoff_confirmed` reçu |
| **Résultat attendu** | Transfert tracé, `handoff_confirmed_at` set, audit log créé |

---

### TF-08 — Idempotence des actions chauffeur

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier qu'une même action soumise deux fois ne crée pas de doublon |
| **Préconditions** | Livraison `SCHEDULED` |
| **Étapes** | 1. `POST /api/driver/deliveries/{id}/pickup` avec header `Idempotency-Key: pkp-{id}`<br>2. Répéter exactement la même requête<br>3. Vérifier que `picked_up_at` est identique dans les deux réponses |
| **Résultat attendu** | Deuxième appel retourne la réponse mise en cache, pas de second enregistrement en base |
| **Critère d'acceptation** | Absence de doublons dans `delivery_status_history` |

---

## 10.2 Tests Non Fonctionnels

### TNF-01 — Tests de charge API (pics du matin)

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier que l'API supporte la charge des affectations matinales (07h00–09h00) |
| **Outil** | k6 / Apache JMeter |
| **Scénario** | 50 chauffeurs simultanés appelant `GET /api/driver/routes/today` + `GET /api/driver/deliveries/active` |
| **Seuils acceptables** | p95 < 500ms, p99 < 1s, taux d'erreur < 1% |
| **Endpoint critique** | `POST /api/driver/deliveries/{id}/pickup` — doit gérer les acceptations simultanées sans race condition (mécanisme `atomicAccept` avec `FOR UPDATE`) |

---

### TNF-02 — Tests de sécurité RBAC

| Cas | Vérification |
|-----|-------------|
| Driver accède à `/api/admin/**` | HTTP 403 |
| Dispatcher accède à `/api/admin/companies/**` | HTTP 403 |
| Token expiré sur `/api/driver/deliveries/active` | HTTP 401 |
| Token d'une entreprise A accède aux données entreprise B | HTTP 200 mais résultat vide (filtre Hibernate companyId) |
| Requête sans token | HTTP 401 |
| SUPER_ADMIN accède à toutes les entreprises | HTTP 200, toutes les données |

---

### TNF-03 — Tests de reprise : sauvegarde / restauration DB

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier que la base est restaurable et que l'outbox reprend normalement |
| **Étapes** | 1. `pg_dump delivery_db` → backup<br>2. Simuler une panne (arrêter le container)<br>3. `pg_restore` sur un nouveau container<br>4. Redémarrer le service<br>5. Vérifier que les événements `PENDING` dans `outbox_event` sont traités au prochain cycle (20s) |
| **Résultat attendu** | Aucun événement perdu, idempotency keys évitent les doublons ERP |

---

### TNF-04 — Tests de reprise : sync ERP après indisponibilité Odoo

| Attribut | Valeur |
|----------|--------|
| **Objectif** | Vérifier que les syncs ERP ratées sont rejouées automatiquement |
| **Étapes** | 1. Éteindre Odoo<br>2. Compléter 3 livraisons → 3 événements `ERP_SYNC_STOCK` PENDING<br>3. Vérifier `retry_count` s'incrémente toutes les 20s<br>4. Rallumer Odoo après 2 minutes<br>5. Vérifier que tous les événements passent à `PROCESSED` |
| **Résultat attendu** | Tolérance jusqu'à 50 × 20s ≈ 16 minutes de downtime Odoo sans perte |

---

## 10.3 Critères d'Acceptation

### CA-01 — Historique complet des événements

> **Critère :** Chaque livraison doit avoir un historique complet de toutes ses transitions de statut.

**Vérification SQL :**
```sql
SELECT dsh.status, dsh.changed_by, dsh.changed_by_role, dsh.changed_at, dsh.note
FROM delivery_status_history dsh
WHERE dsh.delivery_id = '<uuid>'
ORDER BY dsh.changed_at ASC;
```
**Attendu :** Au moins une ligne par transition effectuée, avec `changed_by` et `changed_at` non nuls.

---

### CA-02 — POD disponible < 5 secondes après sync

> **Critère :** La photo POD doit être accessible via l'interface admin en moins de 5 secondes après soumission par le chauffeur.

**Vérification :**
1. Mesurer `T1` = instant de soumission du POD sur l'app mobile.
2. Mesurer `T2` = instant où `GET /api/admin/deliveries/{id}` retourne les URLs photos non nulles.
3. `T2 - T1 < 5 secondes`.

**Implémentation :** Upload MinIO synchrone dans la transaction de `submitPod()` — garanti par l'architecture actuelle.

---

### CA-03 — Absence de doublons d'événements (idempotence)

> **Critère :** Une même action (pickup, transit, POD) ne doit jamais être enregistrée deux fois, même en cas de retry réseau.

**Vérifications :**
```sql
-- Pas de doublon dans l'historique de statut
SELECT status, COUNT(*) FROM delivery_status_history
WHERE delivery_id = '<uuid>'
GROUP BY status
HAVING COUNT(*) > 1;
-- Attendu : 0 lignes

-- Pas de doublon POD
SELECT COUNT(*) FROM proof_of_delivery WHERE delivery_id = '<uuid>';
-- Attendu : 1

-- Pas de doublon outbox traité
SELECT event_type, COUNT(*) FROM outbox_event
WHERE payload::jsonb->>'deliveryId' = '<uuid>'
  AND status = 'PROCESSED'
GROUP BY event_type
HAVING COUNT(*) > 1;
-- Attendu : 0 lignes
```

---

### CA-04 — Exports KPI conformes aux données livraisons

> **Critère :** Les KPIs affichés dans le rapport de tournée doivent correspondre aux données réelles en base.

**Vérification :**
```sql
-- Compter manuellement les statuts pour une tournée
SELECT rs.completion_status, COUNT(*)
FROM route_stops rs
WHERE rs.route_id = '<uuid>'
GROUP BY rs.completion_status;
```
Comparer avec les valeurs `taux_completion`, `stops_on_time`, `stops_late` dans `route_report.payload`.

**Attendu :** Écart ≤ 0 (calcul déterministe via `DelayCalculationService`).

---

## 10.4 Matrice de Traçabilité

| Exigence PFE | Test(s) couvrant |
|-------------|-----------------|
| Création livraison depuis ERP | TF-01 |
| Affectation tournée + réception mobile | TF-02 |
| Mode offline : collecte puis sync | TF-03 |
| POD consultable côté Web après sync | TF-04, CA-02 |
| Statut final remonté dans l'ERP | TF-05 |
| Tests charge API (pics matin) | TNF-01 |
| Tests sécurité RBAC + tokens | TNF-02 |
| Tests reprise sauvegarde/restauration | TNF-03, TNF-04 |
| Historique complet par livraison | TF-02, CA-01 |
| POD < 5s après sync | TF-04, CA-02 |
| Absence doublons (idempotence) | TF-08, CA-03 |
| Exports KPI conformes | CA-04 |

---

## 10.5 Environnement de Test

| Composant | Configuration recommandée |
|-----------|--------------------------|
| Stack | `docker compose up` — même `docker-compose.yml` que la production |
| Base de données | Containers dédiés `postgres-delivery-test`, `postgres-app-test` |
| ERP | Instance Odoo 19 de test avec données de démo |
| Mobile | Appareil Android physique (pas d'émulateur — GPS requis pour TF-04) |
| Réseau offline | Mode avion Android pour TF-03 |

