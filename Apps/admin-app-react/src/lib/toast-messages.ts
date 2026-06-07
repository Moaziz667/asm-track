/**
 * Enhanced toast messages with:
 * - Fixed typos
 * - Informative context
 * - Rich descriptions
 * - Actionable language
 */

export const messages = {
  // ──── ROUTES (Tournées) ────────────────────────────────────────────────────
  routes: {
    loadFailed: {
      title: 'Impossible de charger les tournées',
      description: 'Vérifiez votre connexion et réessayez',
    },
    loadDriverRoutesFailed: {
      title: 'Impossible de charger les tournées du chauffeur',
      description: 'Vérifiez votre connexion et réessayez',
    },
    validationSuccess: {
      title: '✓ Tournée validée',
      description: 'Les arrêts sont maintenant planifiés',
    },
    validationFailed: {
      title: 'Validation échouée',
      description: 'Vérifiez les arrêts sans coordonnées GPS',
    },
    missingGPS: {
      title: 'Arrêts sans coordonnées GPS détectés',
      description: 'Épinglez-les sur la carte avant de valider',
    },
    cancelSuccess: {
      title: '✓ Tournée annulée',
      description: 'Les livraisons ont été remises en attente. Les colis ramassés restent avec le chauffeur.',
    },
    cancelFailed: {
      title: 'Impossible d\'annuler la tournée',
      description: 'Une erreur s\'est produite lors du traitement',
    },
    closeSuccess: {
      title: '✓ Tournée clôturée',
      description: 'Aucune autre modification n\'est possible',
    },
    closeFailed: {
      title: 'Impossible de clôturer la tournée',
      description: 'Vérifiez que tous les arrêts sont complétés',
    },
    deleteSuccess: {
      title: '✓ Tournée supprimée',
      description: 'Les arrêts ont été supprimés',
    },
    deleteFailed: {
      title: 'Impossible de supprimer la tournée',
      description: 'La tournée est peut-être en cours d\'exécution',
    },
    capacityWarning: {
      title: 'Capacité dépassée',
      description: 'Cochez "Forcer" si vous souhaitez contourner cette limite',
    },
    reportDownloaded: {
      title: '✓ Rapport téléchargé',
    },
    reportFailed: {
      title: 'Impossible de télécharger le rapport',
      description: 'Vérifiez votre connexion et réessayez',
    },
  },

  // ──── DELIVERIES (Livraisons) ──────────────────────────────────────────────
  deliveries: {
    loadFailed: {
      title: 'Impossible de charger les livraisons',
      description: 'Vérifiez votre connexion et réessayez',
    },
    createSuccess: {
      title: '✓ Livraison créée',
      description: 'Elle apparaîtra dans le tableau dans quelques secondes',
    },
    createFailed: {
      title: 'Impossible de créer la livraison',
      description: 'Vérifiez les informations et réessayez',
    },
    reassignSuccess: {
      title: '✓ Réaffectation réussie',
      description: 'Les livraisons ont été assignées au nouveau chauffeur',
    },
    reassignToDraftSuccess: (count: number) => ({
      title: `✓ ${count} livraison${count > 1 ? 's' : ''} ajoutée${count > 1 ? 's' : ''}`,
      description: 'Ajoutée(s) à la tournée brouillon pour planification',
    }),
    reassignToActiveSuccess: (count: number) => ({
      title: `✓ ${count} livraison${count > 1 ? 's' : ''} réaffectée${count > 1 ? 's' : ''}`,
      description: 'Le chauffeur a été notifié en temps réel',
    }),
    reassignPartialSuccess: (success: number, failed: number) => ({
      title: `${success} réussite${success > 1 ? 's' : ''} · ${failed} échec${failed > 1 ? 's' : ''}`,
      description: 'Certaines livraisons n\'ont pas pu être transférées',
    }),
    reassignPartialFailed: {
      title: 'Réaffectation partielle',
      description: 'Certaines livraisons n\'ont pas pu être transférées. Vérifiez les contraintes.',
    },
    reassignFailed: {
      title: 'Impossible de réaffecter',
      description: 'Vérifiez les horaires et les capacités',
    },
    timeWindowRequired: {
      title: 'Fenêtres de temps requises',
      description: 'Définissez les horaires de début et fin pour cette livraison',
    },
    noteRequiredInField: {
      title: 'Note de passation obligatoire',
      description: 'Expliquez le transfert pour les livraisons déjà en cours de livraison',
    },
    noteRequiredForActiveRoute: {
      title: 'Note obligatoire',
      description: 'Justifiez le transfert vers une tournée actuelle ou validée',
    },
    inFieldRestriction: {
      title: 'Livraison déjà en transit',
      description: 'Impossible de modifier une livraison en cours de livraison',
    },
  },

  // ──── DRIVERS (Chauffeurs) ─────────────────────────────────────────────────
  drivers: {
    loadFailed: {
      title: 'Impossible de charger les chauffeurs',
      description: 'Vérifiez votre connexion',
    },
    updateSuccess: {
      title: '✓ Chauffeur mis à jour',
    },
    updateFailed: {
      title: 'Impossible de mettre à jour',
      description: 'Vérifiez les informations saisies',
    },
    inviteSent: {
      title: '✓ Invitation envoyée',
      description: 'Le chauffeur recevra un email avec les instructions',
    },
    inviteFailed: {
      title: 'Impossible d\'envoyer l\'invitation',
      description: 'Vérifiez l\'adresse email et réessayez',
    },
    statusChangeFailed: {
      title: 'Impossible de changer le statut',
    },
    deactivateSuccess: {
      title: '✓ Chauffeur désactivé',
      description: 'Il ne pourra plus se connecter',
    },
    deactivateFailed: {
      title: 'Impossible de désactiver le chauffeur',
    },
    importSuccess: (count: number) => ({
      title: `✓ ${count} chauffeur${count > 1 ? 's' : ''} importé${count > 1 ? 's' : ''}`,
      description: 'Ils peuvent maintenant se connecter à l\'application',
    }),
    importFailed: {
      title: 'Erreur lors de l\'importation',
      description: 'Vérifiez le format du fichier CSV',
    },
    nameRequired: {
      title: 'Nom et téléphone requis',
    },
    emailRequired: {
      title: 'Email requis',
      description: 'Nécessaire pour envoyer l\'invitation de connexion',
    },
  },

  // ──── IMPORT (Importation Commandes) ───────────────────────────────────────
  import: {
    loadFailed: {
      title: 'Impossible de charger les commandes',
      description: 'Vérifiez votre connexion',
    },
    previewFailed: {
      title: 'Impossible de charger l\'aperçu',
      description: 'Le fichier est peut-être corrompu',
    },
    importSuccess: (imported: number, skipped: number) => {
      const msg = `✓ ${imported} commande${imported > 1 ? 's' : ''} importée${imported > 1 ? 's' : ''}`;
      const skip = skipped > 0 ? ` · ${skipped} ignorée${skipped > 1 ? 's' : ''}` : '';
      return { title: msg + skip };
    },
    importBatchFailed: {
      title: 'Erreur lors de l\'importation en masse',
      description: 'Certaines commandes n\'ont pas pu être traitées',
    },
    importSingleSuccess: {
      title: '✓ Commande importée',
      description: 'Elle apparaîtra dans le tableau dans quelques secondes',
    },
    alreadyImported: {
      title: 'Commande déjà importée',
      description: 'Cette commande existe déjà dans le système',
    },
    importSingleFailed: {
      title: 'Impossible d\'importer la commande',
      description: 'Vérifiez les données et réessayez',
    },
  },

  // ──── DEPOTS (Hubs/Dépôts) ────────────────────────────────────────────────
  depots: {
    loadFailed: {
      title: 'Impossible de charger les hubs',
    },
    createSuccess: {
      title: '✓ Hub créé',
    },
    updateSuccess: {
      title: '✓ Hub mis à jour',
    },
    createFailed: {
      title: 'Impossible de créer le hub',
      description: 'Vérifiez les informations saisies',
    },
    updateFailed: {
      title: 'Impossible de mettre à jour',
    },
    deleteFailed: {
      title: 'Impossible de supprimer le hub',
      description: 'Il contient peut-être des données',
    },
    geolocateSuccess: {
      title: '✓ Position localisée',
      description: 'Les coordonnées GPS ont été mises à jour',
    },
    geolocateFailed: {
      title: 'Emplacement introuvable',
      description: 'Vérifiez l\'adresse et réessayez',
    },
    syncSuccess: {
      title: '✓ Adresse synchronisée',
    },
    syncFailed: {
      title: 'Erreur de synchronisation',
      description: 'L\'adresse n\'a pas pu être vérifiée',
    },
    configError: {
      title: 'Erreur de configuration',
      description: 'L\'adresse est invalide ou incomplète',
    },
    addressRequired: {
      title: 'Adresse requise',
    },
    nameRequired: {
      title: 'Nom du hub requis',
    },
  },

  // ──── ZONES ────────────────────────────────────────────────────────────────
  zones: {
    loadFailed: {
      title: 'Impossible de charger les zones',
    },
    createSuccess: {
      title: '✓ Zone créée',
    },
    updateSuccess: {
      title: '✓ Zone mise à jour',
    },
    createFailed: {
      title: 'Impossible de créer la zone',
    },
    updateFailed: {
      title: 'Impossible de mettre à jour la zone',
    },
    deleteFailed: {
      title: 'Impossible de supprimer la zone',
    },
    codeAlreadyAdded: {
      title: 'Code postal déjà ajouté',
      description: 'Ce code existe déjà dans cette zone',
    },
    codeLookupFailed: {
      title: 'Code postal introuvable',
      description: 'Vérifiez le code et réessayez',
    },
    networkError: {
      title: 'Erreur réseau',
      description: 'Vérifiez votre connexion',
    },
    syncSuccess: {
      title: '✓ Synchronisation terminée',
      description: 'Toutes les livraisons ont été réattribuées aux zones correspondantes',
    },
    syncFailed: {
      title: 'Synchronisation échouée',
      description: 'Les zones n\'ont pas pu être synchronisées',
    },
    nameRequired: {
      title: 'Nom de zone requis',
    },
    minPostalCodesRequired: {
      title: 'Au moins un code postal requis',
    },
    conflictingCodes: (count: number) => ({
      title: `${count} code${count > 1 ? 's' : ''} déjà assigné${count > 1 ? 's' : ''}`,
      description: 'Supprimez-le(s) des zones existantes d\'abord',
    }),
  },

  // ──── GENERAL ──────────────────────────────────────────────────────────────
  general: {
    undoSuccess: {
      title: '✓ Action annulée',
      description: 'L\'état précédent a été restauré',
    },
    undoFailed: {
      title: 'Impossible d\'annuler l\'action',
      description: 'État restauré à partir du serveur',
    },
    actionFailed: {
      title: 'Action échouée',
      description: 'Veuillez réessayer',
    },
  },
};
