# Guide Utilisateur — Super Administrateur (Plateforme SaaS)

**Application :** ASM Track Super Admin  
**Rôle :** `SUPER_ADMIN` — développeur / propriétaire de la plateforme SaaS  
**Accès :** Interface dédiée séparée de l'admin web app

!!! warning "Audience"
    Ce guide s'adresse uniquement à l'équipe technique qui opère la plateforme ASM Track. Les entreprises clientes n'ont pas accès à cette interface.

---

## 1. Connexion

1. Ouvrir l'interface Super Admin.
2. Saisir votre **email** et **mot de passe** de compte super-admin.
3. Cliquer **Se connecter**.

!!! info
    Le compte super-admin n'a pas de `companyId` dans son JWT — il voit toutes les données de toutes les entreprises sans filtre de tenant.

---

## 2. Gestion des Entreprises

Menu **Entreprises** — création et configuration des clients SaaS.

### 2.1 Créer une entreprise

1. Cliquer **Nouvelle entreprise**.
2. Remplir :
    - **Nom** de l'entreprise
    - **Adresse**
    - **Couleur principale** (hex, ex : `#FF5722`) — utilisée dans l'interface admin de l'entreprise
    - **Email support**
3. Cliquer **Créer**.

### 2.2 Configurer l'intégration ERP

Depuis la fiche de l'entreprise → onglet **ERP** :

| Champ | Description |
|-------|-------------|
| **Type ERP** | `ODOO` ou `NONE` |
| **URL API** | ex : `http://odoo-client.example.com:8069/jsonrpc` |
| **Base de données** | Nom de la base Odoo |
| **Nom d'utilisateur** | Utilisateur Odoo (ex : `admin`) |
| **UID** | ID numérique de l'utilisateur Odoo |
| **Clé API** | Mot de passe ou clé API Odoo |

!!! tip
    Chaque entreprise a sa propre configuration ERP isolée. Le service ERP Adapter utilise cette configuration pour router les synchronisations de stock vers le bon Odoo.

### 2.3 Uploader un logo

Fiche entreprise → **Logo** → sélectionner un fichier image → **Enregistrer**.

### 2.4 Désactiver une entreprise

Fiche entreprise → **Désactiver**. L'entreprise et ses utilisateurs ne peuvent plus se connecter.

---

## 3. Gestion des Utilisateurs Admin

Menu **Utilisateurs** — création des comptes pour les équipes des entreprises clientes.

### 3.1 Créer un compte

1. Cliquer **Nouvel utilisateur**.
2. Remplir :
    - **Nom**, **email**, **mot de passe initial**
    - **Rôle** : `ADMIN` / `DISPATCHER` / `MANAGER`
    - **Entreprise** : associer à une entreprise cliente
3. Cliquer **Créer**.

### 3.2 Rôles disponibles pour les entreprises clientes

| Rôle | Accès dans l'admin web app |
|------|---------------------------|
| `ADMIN` | Gestion complète de l'entreprise (tournées, livraisons, véhicules, dépôts, ERP) |
| `DISPATCHER` | Planification des tournées et suivi des livraisons |
| `MANAGER` | Lecture seule — statistiques et rapports |

### 3.3 Activer / Désactiver un compte

- Bouton **Activer** / **Désactiver** sur la fiche utilisateur.

---

## 4. Gestion des Chauffeurs

!!! info "Pool partagé"
    Les chauffeurs sont **partagés entre toutes les entreprises**. Un chauffeur n'est pas lié à une entreprise spécifique — n'importe quel dispatcher peut l'affecter à une tournée.

Menu **Chauffeurs** :

### 4.1 Créer un compte chauffeur

1. Cliquer **Nouveau chauffeur**.
2. Remplir : **nom**, **téléphone**, **mot de passe initial**.
3. Cliquer **Créer**.

Le chauffeur peut se connecter immédiatement sur l'app mobile Flutter.

### 4.2 Activer / Désactiver

- Bouton **Activer** / **Désactiver** sur la fiche chauffeur.
- Un chauffeur désactivé ne peut plus se connecter à l'app mobile.

### 4.3 Réinitialiser le mot de passe

Fiche chauffeur → **Réinitialiser le mot de passe** → saisir le nouveau mot de passe.

### 4.4 Rapport de performance PDF

Fiche chauffeur → **Télécharger le rapport** → PDF avec statistiques (taux de livraison, historique, délais moyens).

---

## 5. Gestion des Véhicules

!!! info "Flotte plateforme"
    Comme les chauffeurs, les véhicules appartiennent à la plateforme et sont partagés entre les entreprises clientes. Seul le `SUPER_ADMIN` peut les créer ou modifier. Les admins et dispatchers peuvent les **consulter** pour les affecter à des tournées.

Menu **Véhicules** — flotte globale de la plateforme.

### 5.1 Ajouter un véhicule

1. Cliquer **Nouveau véhicule**.
2. Remplir : **immatriculation**, **type** (`CAMION` / `VAN` / `VOITURE` / `MOTO`), **charge utile (kg)**, **marque**, **modèle**.
3. Cliquer **Créer**.

### 5.2 Changer le statut

| Statut | Description |
|--------|-------------|
| `DISPONIBLE` | Peut être assigné à une tournée |
| `EN_MAINTENANCE` | Temporairement indisponible |
| `HORS_SERVICE` | Définitivement retiré |

### 5.3 Supprimer un véhicule

Uniquement si le véhicule n'a jamais été assigné à une tournée.

---

## 6. Vue Tournées (Lecture Seule)

Menu **Tournées** — vue globale cross-entreprises, toutes les tournées de toutes les entreprises.

!!! note
    Le SUPER_ADMIN ne peut pas créer ni modifier de tournées depuis cette interface. La gestion opérationnelle appartient aux admins et dispatchers de chaque entreprise.

---

## 7. Journaux d'Audit

Menu **Audit** — toutes les actions de tous les utilisateurs de toutes les entreprises.

- Filtre par entreprise, acteur, type d'action, période.
- Utile pour le débogage ou les audits de conformité cross-tenant.

---

## 8. Paramètres du Compte

Menu **Paramètres** :

- Changer le mot de passe du compte super-admin.
- Se déconnecter.
