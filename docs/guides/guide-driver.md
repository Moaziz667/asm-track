# Guide Utilisateur — Chauffeur (App Mobile)

**Application :** ASM Track Driver — App Flutter  
**Plateformes :** Android  
**Version minimum Android :** 8.0 (API 26)

---

## 1. Première Installation

1. Télécharger et installer le fichier `.apk` fourni par votre responsable.
2. Autoriser l'installation depuis une source inconnue si demandé.
3. Au premier lancement, autoriser :
   - **Localisation** (GPS) — obligatoire pour valider les livraisons
   - **Notifications** — pour recevoir les affectations de tournées

---

## 2. Connexion

1. Saisir votre **numéro de téléphone** et **mot de passe**.
2. Appuyer sur **Se connecter**.

> En cas d'oubli du mot de passe, contacter votre dispatcher.

---

## 3. Écran Principal

L'écran principal affiche :

- **Livraison active** — si une livraison est en cours, elle apparaît en premier
- **Livraisons disponibles** — commandes non encore affectées dans votre zone
- **Mes tournées** — tournées planifiées par le dispatcher
- **Historique** — livraisons terminées

---

## 4. Accepter une Livraison

> Cette option est disponible uniquement si vous n'êtes pas assigné à une tournée planifiée.

1. Onglet **Disponible** → liste des livraisons libres dans votre ville.
2. Appuyer sur une livraison pour voir les détails (adresse, client, articles).
3. Appuyer **Accepter**.

> ⚠️ En cas de conflit (un autre chauffeur accepte en même temps), un message d'erreur s'affiche. Revenir à la liste.

---

## 5. Suivre une Tournée Planifiée

Quand votre dispatcher crée et valide une tournée pour vous :

1. Notification push reçue : *"Nouvelle tournée assignée"*.
2. Onglet **Mes Tournées** → appuyer sur la tournée du jour.
3. Liste des arrêts ordonnés avec adresses et créneaux horaires.
4. Appuyer **Démarrer la tournée** quand vous partez du dépôt.



---

## 6. Cycle de Livraison

```
ACCEPTÉ (SCHEDULED)
    ↓
Appuyer [Ramasser le colis]
    ↓
RAMASSÉ (PICKED_UP)
    ↓
Appuyer [Démarrer le transit]
    ↓
EN TRANSIT (IN_TRANSIT)
    ↓
Arriver chez le client → Appuyer [Valider la livraison]
    ↓
Formulaire POD (photos + optionnel : commentaire, GPS)
    ↓
LIVRÉ (DELIVERED)
```

### 6.1 Ramasser le colis

- Sur la page de la livraison → appuyer **Ramasser le colis**.
- Confirmer dans la boîte de dialogue.

### 6.2 Démarrer le transit

- Appuyer **Démarrer le transit**.
- L'app enregistre votre position GPS de départ.
- Un itinéraire vers le client est calculé automatiquement.

### 6.3 Valider la livraison (POD)

1. Arriver chez le client.
2. Appuyer **Valider la livraison**.
3. Remplir le formulaire :
   - 📷 **Photo du bon de livraison** (obligatoire) — photo du document signé par le client
   - 📷 **Photo du colis** (obligatoire) — état du colis au moment de la remise
   - 💬 **Commentaire** (optionnel)
   - 📍 **GPS** (toggle) — cocher pour inclure votre position
4. Appuyer **Soumettre**.

> Les photos sont téléchargées sur le serveur. Le dispatcher peut les consulter immédiatement.

### 6.4 Livraison partielle

Si vous ne pouvez livrer qu'une partie des articles :

1. Appuyer **Livraison partielle**.
2. Sélectionner les articles livrés et saisir les quantités.
3. Compléter le formulaire POD normalement.

> Un backorder sera créé  par le dispatcher pour les articles restants.

---

## 7. Signaler un Échec

Si la livraison est impossible :

1. Appuyer **Marquer comme échoué**.
2. Choisir le motif :
   - **Client absent**
   - **Refus du client**
   - **Mauvaise adresse**
   - **Colis endommagé**
   - **Autre**
3. Ajouter un commentaire (recommandé).
4. Confirmer.

> Le dispatcher est notifié immédiatement. Il peut replanifier la livraison.

---

## 8. Mode Hors Ligne

> L'app fonctionne **sans connexion internet**. Toutes vos actions sont sauvegardées localement.

**Comment ça marche :**

1. Vous effectuez une action (ramassage, transit, POD) sans connexion.
2. L'action est stockée dans la file d'attente locale (Hive).
3. L'horodatage exact de votre action est conservé.
4. Quand la connexion revient → l'app envoie automatiquement toutes les actions en attente.
5. Le serveur enregistre les actions avec l'heure réelle où vous les avez effectuées (pas l'heure de reconnexion).


> ⚠️ Les actions en attente sont conservées jusqu'à 24 heures. Ne pas éteindre le téléphone trop longtemps sans connexion.

---

## 9. Collecte de Paiement (COD)

Pour les livraisons avec paiement à la livraison :

1. Badge **"COD"** visible sur la livraison.
2. Après validation, l'app demande : *"Avez-vous collecté le paiement ?"*
3. Confirmer et saisir le montant collecté.

---

## 10. Transfert de Colis (QR Handoff)

Si votre tournée est réaffectée à un autre chauffeur alors que vous avez déjà le colis :

**Chauffeur sortant (vous avez le colis) :**
1. Appuyer **Générer le QR** sur la livraison.
2. Un code QR s'affiche — valide 5 minutes.
3. Faire scanner ce QR par le chauffeur entrant.

**Chauffeur entrant (vous récupérez le colis) :**
1. Recevoir la notification de transfert.
2. Onglet **Transferts** → appuyer sur la livraison.
3. Appuyer **Scanner le QR** → pointer la caméra sur le code du chauffeur sortant.
4. Confirmation automatique — la livraison passe en `PICKED_UP` pour vous.

---

## 11. Signaler un Incident

Pour tout problème (accident, panne, vol...) :

1. Menu ≡ → **Signaler un incident**.
2. Choisir le type d'incident.
3. Décrire la situation.
4. Joindre des photos si possible.
5. Appuyer **Envoyer**.

---

## 12. Mon Profil et Statistiques

Onglet **Profil** :
- Voir votre score de livraisons (livré / échoué / annulé)
- Changer votre mot de passe
- Se déconnecter

---

## 13. Questions Fréquentes

**Je ne reçois pas la notification de ma tournée ?**  
Vérifier que les notifications sont autorisées dans les paramètres Android. Rafraîchir l'onglet Tournées manuellement.

**L'app ne trouve pas ma position GPS ?**  
Vérifier que la localisation est activée sur le téléphone. Sortir à l'extérieur si vous êtes dans un bâtiment (le GPS peut prendre 30 secondes à s'initialiser).

**J'ai soumis le POD mais je n'ai pas de connexion ?**  
Votre action est sauvegardée. Attendre d'avoir du réseau — la synchronisation se fait automatiquement.


