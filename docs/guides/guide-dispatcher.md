# Guide Utilisateur — Dispatcher / Admin / Manager

**Application :** ASM Track — Admin Web App  
**Accès :** Login avec email + mot de passe

| Rôle | Qui | Accès |
|------|-----|-------|
| `ADMIN` | Administrateur de l'entreprise | Commandes, tournées, livraisons, import ERP, dépôts, zones, paramètres SLA, backorders |
| `DISPATCHER` | Agent d'exploitation | Commandes, tournées, livraisons, import ERP, suivi temps réel, rapports |
| `MANAGER` | Responsable / directeur | Lecture seule — tableau de bord, rapports, alertes |

---

## 1. Connexion

1. Ouvrir l'application web ASM Track.
2. Saisir votre **email** et **mot de passe**.
3. Cliquer **Se connecter**.

!!! tip "Mot de passe oublié"
    Contacter votre administrateur d'entreprise (rôle `ADMIN`).

---

## 2. Tableau de Bord

À l'ouverture, le tableau de bord affiche :

| Zone | Contenu |
|------|---------|
| **KPIs du jour** | Livraisons en attente, en cours, livrées, échouées |
| **Alertes SLA** | Livraisons hors délai (rouge = critique) |
| **Performance tournées** | Taux de ponctualité, retard cumulé |
| **Activité temps réel** | Flux WebSocket — chaque événement driver apparaît instantanément |

!!! info
    Les alertes SLA se déclenchent automatiquement toutes les **60 secondes**. Un badge rouge apparaît sur la cloche de notifications.

---

## 3. Gestion des Commandes

### 3.1 Importer depuis l'ERP (Odoo)

1. Menu **Commandes** → **Importer depuis Odoo**.
2. La liste des commandes Odoo en attente s'affiche (détectées toutes les 2 minutes).
3. Sélectionner les commandes à importer (cocher ou **Tout sélectionner**).
4. Cliquer **Importer la sélection**.

!!! success
    Chaque commande importée crée automatiquement une livraison au statut **EN ATTENTE**.

!!! note "Idempotence"
    Importer deux fois la même commande Odoo ne crée pas de doublon. Un badge orange apparaît automatiquement quand de nouvelles commandes sont prêtes.

### 3.2 Créer une commande manuellement

1. Menu **Commandes** → **Nouvelle commande**.
2. Remplir : nom client, téléphone, adresse de livraison, articles, montant.
3. Cliquer **Créer**.

---

## 4. Constructeur de Tournées

Le constructeur de tournées est l'outil central de planification (`/route-builder`).

### 4.1 Créer une tournée

1. Menu **Tournées** → **Nouveau**.
2. Choisir : **chauffeur**, **véhicule**, **date**, **dépôt de départ**.
3. Cliquer **Créer la tournée**.

### 4.2 Ajouter des arrêts

**Méthode 1 — Glisser-déposer depuis la table des commandes**

- La table de gauche liste les commandes non planifiées.
- Saisir la poignée `≡` d'une ligne et déposer sur la carte de tournée dans la barre latérale droite.

**Méthode 2 — Glisser-déposer depuis la carte**

- Cliquer un pin de livraison sur la carte.
- Le déposer sur la carte de tournée souhaitée dans la barre latérale.

**Méthode 3 — Ajout en lot**

- Sélectionner plusieurs commandes (cases à cocher dans la table).
- Cliquer **Ajouter à la tournée** dans la barre d'action groupée.

### 4.3 Réordonner les arrêts

- Dans le panneau des arrêts, glisser-déposer les lignes pour changer l'ordre.

### 4.4 Déplacer un arrêt entre tournées

- Glisser un arrêt depuis la liste d'une tournée → déposer sur une autre carte de tournée dans la barre latérale.
- Le transfert se fait immédiatement (mise à jour optimiste + appel API).

### 4.5 Retirer des arrêts en lot

1. Cocher les arrêts à retirer dans le panneau des arrêts.
2. Cliquer **Retirer (N)** dans la barre d'action.

### 4.6 Optimiser l'itinéraire

1. Ouvrir une tournée dans le panneau des arrêts.
2. Cliquer l'icône **Optimiser** (baguette magique).
3. L'ordre des arrêts est recalculé automatiquement par OSRM.
4. La trace de l'itinéraire s'affiche sur la carte.

!!! info "OSRM"
    L'optimisation utilise le routage réel sur le réseau routier tunisien. Elle minimise la distance totale.

### 4.7 Recalculer la géométrie

- Cliquer l'icône **Rafraîchir** dans l'en-tête du panneau des arrêts pour forcer le recalcul sans changer l'ordre.

### 4.8 Valider et envoyer au chauffeur

1. Cliquer **Valider la tournée**.
2. Statut : `BROUILLON` → `VALIDÉE`.
3. Le chauffeur reçoit une notification push sur son app mobile.
4. La tournée apparaît immédiatement dans l'app du chauffeur.

---

## 5. Suivi des Livraisons en Temps Réel

### 5.1 Liste des livraisons

- Menu **Livraisons** → liste paginée avec filtres (statut, date, chauffeur, ville).
- Mise à jour automatique à chaque événement WebSocket — pas besoin de rafraîchir.

### 5.2 Suivi individuel

- Cliquer sur une livraison → page de détail.
- Carte temps réel avec position GPS du chauffeur (mise à jour toutes les 50 m ou 30 s).
- Historique complet des statuts avec horodatage.

### 5.3 Réaffecter une livraison

- Page livraison → **Réaffecter** → choisir un autre chauffeur.

!!! warning
    Si la livraison est déjà `PICKED_UP` (colis physiquement en main), le flow de **transfert QR** est déclenché automatiquement entre les deux chauffeurs.

---

## 6. Rapport de Tournée

!!! info "Rôles"
    Accessible aux rôles `ADMIN`, `DISPATCHER` et `MANAGER`.

Quand une tournée est clôturée (`CLOSED`), le rapport est disponible sur la page de détail :

| Section | Contenu |
|---------|---------|
| **KPIs** | Taux de complétion, ponctualité, distance, durée, retard cumulé |
| **Donut** | Répartition Livrés / Partiels / Échoués / Annulés |
| **Timeline** | Graphique de retard par arrêt |
| **Tableau** | Créneaux, temps réels, statut, mouvements |
| **POD** | Galerie photos + bon de livraison par arrêt |
| **Audit** | 50 dernières transitions de statut |

Cliquer **Télécharger PDF** pour exporter le rapport complet.

---

## 7. Alertes SLA et Notifications

- La **cloche** en haut à droite affiche les événements non lus (max 50).
- Cliquer une alerte → redirige vers la livraison concernée.

| Type d'alerte | Condition de déclenchement | Sévérité |
|---------------|---------------------------|----------|
| `SLA_WAITING` | Commande non affectée trop longtemps | WARNING |
| `SLA_ASSIGNMENT` | Chauffeur affecté mais pas encore parti | CRITICAL |
| `SLA_TRANSIT` | Livraison `IN_TRANSIT` après la fin du créneau horaire prévu | CRITICAL |

---

## 8. Matrice des accès par rôle

### Ce que DISPATCHER peut faire

| Fonction | Endpoints concernés |
|----------|-------------------|
| Importer des commandes depuis Odoo | `GET /api/admin/erp/pending-orders`, `POST /api/admin/erp/import-order/{id}` |
| Créer / modifier / valider des tournées | `POST /api/admin/routes`, `PUT /api/admin/routes/{id}/validate` |
| Ajouter / retirer / réordonner des arrêts | `POST /api/admin/routes/{id}/stops`, `PUT /api/admin/routes/{id}/stops/reorder` |
| Transférer des arrêts entre tournées | `POST /api/admin/routes/transfer-stops` |
| Optimiser l'itinéraire (OSRM) | `POST /api/admin/routes/{id}/optimize` |
| Affecter une livraison à un chauffeur | `POST /api/admin/deliveries/{id}/assign` |
| Réaffecter / replanifier une exception | `POST /api/admin/ops/exceptions/{id}/reassign` |
| Consulter la liste des chauffeurs | `GET /api/admin/drivers` (lecture seule) |
| Tableau de bord OPS, alertes SLA, rapports | `/api/admin/ops/**`, `/api/admin/reports/**` |
| Suivi temps réel (GPS, livraisons) | `/api/admin/deliveries/**` |

### Ce que DISPATCHER ne peut PAS faire

!!! warning "Accès bloqué pour DISPATCHER"

| Fonction | Pourquoi |
|----------|----------|
| Gérer les véhicules (créer, modifier, statut) | Flotte plateforme — réservé à `SUPER_ADMIN` |
| Gérer les dépôts et zones | Configuration de l'entreprise — réservé à `ADMIN` |
| Modifier les paramètres SLA | `/api/admin/reports/settings` — réservé à `ADMIN` |
| Annuler définitivement une commande | Impact ERP — réservé à `ADMIN` |
| Créer des backorders | Décision commerciale — réservé à `ADMIN` |
| Gérer les chauffeurs (créer, activer) | Pool plateforme — réservé à `SUPER_ADMIN` |
| Accéder aux paramètres de l'entreprise | `/api/admin/companies/` — réservé à `SUPER_ADMIN` |

### Ce que MANAGER peut faire (lecture seule)

| Fonction | Endpoints concernés |
|----------|-------------------|
| Tableau de bord KPI | `GET /api/admin/reports/dashboard`, `GET /api/admin/ops/overview` |
| Rapports et analytics | `GET /api/admin/reports/**` (sauf settings) |
| Alertes SLA | `GET /api/admin/ops/alerts` |
| Vue tournées | `GET /api/admin/routes` (lecture) |
| Rapport de clôture PDF | `GET /api/admin/routes/{id}/report/pdf` |

---

## 9. Raccourcis Clavier (Constructeur de Tournées)

| Raccourci | Action |
|-----------|--------|
| `Espace` | Saisir / déposer un arrêt (glisser-déposer clavier) |
| `↑ ↓` | Déplacer l'arrêt sélectionné |
| `Échap` | Annuler le glisser-déposer |

---

## 10. Questions Fréquentes

??? question "La carte ne s'affiche pas ?"
    Vérifier que le service OSRM est en cours d'exécution. Contacter l'administrateur système.

??? question "Une livraison reste EN ATTENTE alors que j'ai créé la tournée ?"
    La livraison doit être ajoutée à la tournée **ET** la tournée doit être **validée** pour que le chauffeur la reçoive.

??? question "Le chauffeur ne voit pas sa tournée ?"
    Vérifier que la tournée est au statut `VALIDÉE` et que le chauffeur est bien connecté à l'app mobile.

??? question "Comment voir les livraisons d'hier ?"
    Menu **Livraisons** → filtrer par date, ou utiliser la vue calendrier dans **Tournées**.
