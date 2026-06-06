import { useLocaleStore, getLocaleFromCookie } from './i18n';
import { EN_COPY } from './en-copy';
import { AR_COPY } from './ar-copy';

export { EN_COPY, AR_COPY };

// ── Shared notification-template helpers ────────────────────────────────────
// Format an ISO timestamp as a locale-aware short clock (e.g. "14:32"). Returns
// '' for missing/invalid values so callers can concatenate safely.
const fmtEtaFor = (locale: string) => (iso: string): string => {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', hour12: false });
};
const fmtEtaFr = fmtEtaFor('fr-FR');
const fmtEtaEn = fmtEtaFor('en-US');
const fmtEtaAr = fmtEtaFor('ar');
// Format a monetary amount with grouped thousands + currency (default TND).
// Returns '' for missing/invalid values so callers can concatenate safely.
const fmtMoneyFor = (locale: string) => (amount: any, currency?: string): string => {
  if (amount == null || amount === '') return '';
  const n = Number(amount);
  if (!isFinite(n)) return '';
  const cur = (currency && String(currency).trim()) || 'TND';
  return `${n.toLocaleString(locale, { maximumFractionDigits: 2 })} ${cur}`;
};
const fmtMoneyFr = fmtMoneyFor('fr-FR');
const fmtMoneyEn = fmtMoneyFor('en-US');
const fmtMoneyAr = fmtMoneyFor('ar');
// "HH:mm" from a backend LocalTime string ("HH:mm:ss"); '' for anything else.
const fmtClock = (v: any): string => {
  if (typeof v !== 'string') return '';
  const m = /^(\d{2}):(\d{2})/.exec(v);
  return m ? `${m[1]}:${m[2]}` : '';
};
// "08:00–12:00" when both ends are present, else a single time, else ''.
const fmtWindow = (start: any, end: any): string => {
  const s = fmtClock(start);
  const e = fmtClock(end);
  if (s && e) return `${s}–${e}`;
  return s || e || '';
};
// Prefix a notification body with the ERP order ref when present.
const refTag = (p: any): string => p?.orderId ? `${p.orderId} · ` : '';
// Pluralize a stop count for FR/EN; AR is gender/number aware separately.
const stopsFr = (n: number) => `${n} arrêt${n > 1 ? 's' : ''}`;
const stopsEn = (n: number) => `${n} stop${n > 1 ? 's' : ''}`;
// Export so en-copy.ts and ar-copy.ts can reuse the same helpers without
// re-implementing the UUID/ISO/pluralization rules.
export const notifHelpers = {
  fmtEtaFr, fmtEtaEn, fmtEtaAr,
  fmtMoneyFr, fmtMoneyEn, fmtMoneyAr,
  fmtClock, fmtWindow,
  refTag, stopsFr, stopsEn,
};

export const FR_COPY = {
  // ── Actions (libellés boutons) ────────────────────────────────────────
  actions: {
    cancelRoute: 'Annuler la tournée',
    validateRoute: 'Valider la tournée',
    reassignRoute: 'Réaffecter la tournée',
    closeRoute: 'Clôturer la tournée',
    deleteDraft: 'Supprimer le brouillon',
    addStop: 'Ajouter un arrêt',
    removeStop: 'Retirer cet arrêt',
    pinLocation: 'Épingler la position GPS',
    confirm: 'Confirmer',
    cancel: 'Annuler',
    close: 'Fermer',
    back: '← Retour',
    refresh: 'Actualiser',
    save: 'Enregistrer',
    create: 'Créer',
    edit: 'Modifier',
    delete: 'Supprimer',
    view: 'Voir les détails',
    export: 'Exporter',
    import: 'Importer',
    search: 'Rechercher',
    filter: 'Filtrer',
    clearFilters: 'Effacer les filtres',
    newRoute: 'Nouvelle tournée',
    newVehicle: 'Nouveau véhicule',
    newDriver: 'Nouveau chauffeur',
    // Delivery exception actions
    reassignDelivery: 'Réaffecter à un chauffeur',
    transferToRoute: 'Transférer vers une tournée',
    unassignDelivery: 'Renvoyer au pool',
    bulkReassign: 'Réaffecter en lot',
    swapDeliveries: 'Échanger les livraisons',
    postpone: 'Reporter au lendemain',
    returnToDepot: 'Retour au dépôt puis redispatch',
    handoverInField: 'Passation sur le terrain',
  },

  // ── Infobulles (title des boutons icônes) ────────────────────────────
  tooltips: {
    cancelRoute: 'Annuler définitivement cette tournée',
    validateRoute: 'Passer la tournée en statut Validée',
    reassignRoute: 'Affecter cette tournée à un autre chauffeur',
    closeRoute: 'Clôturer manuellement la tournée',
    deleteDraft: 'Supprimer ce brouillon de tournée',
    viewDetail: 'Voir les détails de cet élément',
    addStop: 'Ajouter une livraison à cette tournée',
    removeStop: 'Retirer cet arrêt de la tournée',
    pinLocation: 'Définir manuellement les coordonnées GPS',
    refresh: 'Actualiser les données',
    export: 'Exporter les données',
    // Delivery exception tooltips
    reassignDelivery: 'Transférer cette livraison vers un autre chauffeur (garde l\'ownership)',
    transferToRoute: 'Placer cette livraison dans une tournée existante à la position choisie',
    unassignDelivery: 'Retirer de la tournée et renvoyer au pool non-affectées (dispatch plus tard)',
    capacityOver: 'Capacité véhicule dépassée — choisissez un autre véhicule ou divisez la livraison',
    capacityOk: 'Capacité véhicule disponible',
  },

  // ── États vides ──────────────────────────────────────────────────────
  empty: {
    routes: 'Aucune tournée ne correspond aux filtres sélectionnés',
    deliveries: 'Aucune livraison ne correspond aux critères sélectionnés',
    drivers: 'Aucun chauffeur enregistré — commencez par ajouter un chauffeur',
    vehicles: 'Aucun véhicule dans le parc — ajoutez un premier véhicule',
    depots: 'Aucun dépôt configuré',
    zones: 'Aucune zone géographique définie',
    stops: 'Aucun arrêt dans cette tournée',
    history: 'Aucun historique disponible pour cet élément',
    pod: 'Aucune preuve de livraison disponible pour cet arrêt',
    exceptions: 'Aucune exception à traiter — tout est en ordre',
    notifications: 'Aucune notification en attente',
    auditLogs: 'Aucun journal d\'audit disponible',
    search: 'Aucun résultat pour cette recherche',
    generic: 'Aucun résultat',
  },

  // ── Erreurs toast ────────────────────────────────────────────────────
  errors: {
    missingGps: 'Impossible de valider — des arrêts n\'ont pas de coordonnées GPS',
    cancelFailed: 'Erreur lors de l\'annulation de la tournée',
    reassignFailed: 'Erreur lors de l\'réaffectation',
    validateFailed: 'Erreur lors de la validation de la tournée',
    closeFailed: 'Erreur lors de la clôture de la tournée',
    deleteFailed: 'Erreur lors de la suppression',
    loadFailed: 'Impossible de charger les données — réessayez',
    saveFailed: 'Erreur lors de l\'enregistrement',
    importFailed: 'Erreur lors de l\'importation',
    exportFailed: 'Erreur lors de l\'exportation',
    networkError: 'Erreur réseau — vérifiez votre connexion',
    // Reassign/transfer errors
    capacityExceeded: 'Capacité véhicule dépassée pour cette tournée',
    stopOrderInvalid: 'Position invalide — doit être entre 1 et le nombre d\'arrêts + 1',
    timeWindowInvalid: 'Créneau horaire en dehors des bornes de la tournée',
    unassignFailed: 'Erreur lors du renvoi au pool',
    transferFailed: 'Erreur lors du transfert',
  },

  // ── Succès toast ─────────────────────────────────────────────────────
  success: {
    routeCancelled: 'Tournée annulée — livraisons planifiées remises en attente',
    routeValidated: 'Tournée validée avec succès',
    routeClosed: 'Tournée clôturée',
    routeDeleted: 'Brouillon supprimé',
    routeReassigned: 'Tournée réaffectée',
    saved: 'Enregistrement réussi',
    created: 'Création réussie',
    deleted: 'Suppression réussie',
    exported: 'Export réalisé avec succès',
    imported: 'Importation réussie',
    deliveryReassigned: 'Livraison réaffectée',
    deliveryUnassigned: 'Livraison renvoyée au pool',
    deliveryTransferred: 'Livraison transférée vers la tournée cible',
  },

  // ── Dialogues de confirmation ────────────────────────────────────────
  confirm: {
    cancelRoute: {
      title: 'Annuler la tournée ?',
      body: 'Les livraisons planifiées seront remises en attente. Les colis déjà ramassés restent avec le chauffeur.',
      confirm: 'Oui, annuler',
    },
    deleteRoute: {
      title: 'Supprimer ce brouillon ?',
      body: 'Cette action est irréversible. Le brouillon sera définitivement supprimé.',
      confirm: 'Supprimer',
    },
    closeRoute: {
      title: 'Clôturer la tournée ?',
      body: 'La tournée sera marquée comme clôturée. Cette action ne peut pas être annulée.',
      confirm: 'Clôturer',
    },
    deleteVehicle: {
      title: 'Supprimer ce véhicule ?',
      body: 'Le véhicule sera retiré du parc. Cette action est irréversible.',
      confirm: 'Supprimer',
    },
    deleteDriver: {
      title: 'Supprimer ce chauffeur ?',
      body: 'Le profil chauffeur sera supprimé du système.',
      confirm: 'Supprimer',
    },
  },

  // ── Placeholders champs ──────────────────────────────────────────────
  placeholders: {
    searchRoutes: 'Rechercher une tournée...',
    searchDeliveries: 'Client, référence, ID...',
    searchDrivers: 'Nom ou téléphone du chauffeur...',
    searchVehicles: 'Marque, modèle, immatriculation...',
    searchGeneric: 'Rechercher...',
    reason: 'Raison ',
    city: 'Ville...',
    date: 'Date',
    notes: 'Notes ou instructions...',
  },

  // ── Labels sections et pages ─────────────────────────────────────────
  pages: {
    routes: {
      title: 'Tournées',
      subtitle: 'Planification et suivi des tournées de livraison',
    },
    routeDetail: {
      backLabel: '← Retour aux tournées',
      kpiProgress: 'Progression',
      kpiDelay: 'Retard cumulé',
      kpiSla: 'Ponctualité SLA',
      kpiRevenue: 'Revenu total',
      kpiDistance: 'Distance totale',
      kpiDriver: 'Chauffeur',
      kpiVehicle: 'Véhicule',
      stopsTitle: 'Séquence de livraison',
      orderInfo: 'Informations commande',
      statusLog: 'Suivi du statut',
      pod: 'Preuve de livraison',
      legacyTitle: 'Arrêts retirés de la tournée',
      sidebarDriver: 'Chauffeur assigné',
      sidebarVehicle: 'Véhicule',
      sidebarDepot: 'Dépôt de départ',
      sidebarMetrics: 'Métriques de tournée',
      sidebarLifecycle: 'Cycle de vie',
    },
    deliveries: {
      title: 'Qualification',
      subtitle: 'Pipeline de qualification opérationnelle des livraisons',
      // Quick views
      quickViewAll: 'Tous les statuts',
      quickViewNeedsPinning: 'Géo-fixation Requis',
      quickViewUnassigned: 'En attente assignation',
      quickViewInTransit: 'Flux en route',
      quickViewCompleted: 'Livraisons validées',
      quickViewFailed: 'Incidents & Retours',
      // Filter labels
      filterLabel: 'Filtres',
      showFilters: 'Afficher les filtres',
      hideFilters: 'Réduire les filtres',
      filterByStatus: 'Tous les statuts',
      filterByDriver: 'Sans filtre chauffeur',
      filterByZone: 'Secteur Global',
      filterClear: 'Effacer les filtres',
      searchPlaceholder: 'Rechercher par référence, client, adresse...',
      // Tabs
      tabList: 'Liste',
      tabMap: 'Carte',
      // Table headers
      refHeader: 'Référence',
      clientHeader: 'Client',
      addressHeader: 'Adresse',
      driverHeader: 'Chauffeur',
      zoneHeader: 'Secteur',
      statusHeader: 'Statut',
      // Buttons & Actions
      pinButton: 'Épingler',
      cancelButton: 'Annuler livraison',
      downloadBl: 'Télécharger BL',
      trackingLink: 'Lien de suivi',
      // Modals & Messages
      pinModalTitle: 'Validation Géo-fixation',
      pinModalSearch: 'Rechercher une adresse...',
      pinModalConfirm: 'Position confirmée',
      pinReverseGeocoding: 'Non géocoulissé...',
      cancelModalTitle: 'Annuler la livraison',
      cancelModalLabel: 'Raison (optionnel)',
      cancelModalPlaceholder: 'Expliquez pourquoi cette livraison est annulée...',
      // Messages
      backorderCreated: 'Backorder créé',
      deliveryCreated: 'Livraison créée',
      deliveryCancelled: 'Livraison annulée',
      trackingCopied: 'Lien de suivi copié !',
      loadError: 'Erreur de chargement',
      backorderError: 'Erreur backorder',
      pinError: 'Erreur de géo-fixation',
      downloadError: 'Erreur du téléchargement du BL',
      unknownDriver: 'Affectation Inconnue',
      outOfZone: 'Hors zone',
      lockedGeocoding: 'Qualification Verrouillée',
      // Pagination & Layout
      totalFlow: 'Flux Total',
    },
    drivers: {
      title: 'Chauffeurs',
      subtitle: 'Gestion de l\'effectif et suivi opérationnel',
    },
    vehicles: {
      title: 'Parc véhicules',
      subtitle: 'Gestion de la flotte et actifs roulants',
    },
    dashboard: {
      title: 'Tableau de bord',
      subtitle: 'Centre de commandement opérationnel',
    },
    depots: {
      title: 'Dépôts',
      subtitle: 'Configuration des sites de départ et d\'arrivée',
    },
    zones: {
      title: 'Zones géographiques',
      subtitle: 'Découpage territorial pour l\'optimisation des tournées',
    },
    exceptions: {
      title: 'Exceptions',
      subtitle: 'Incidents, échecs de livraison, renvoi au pool',
    },
    dispatch: {
      title: 'Centre de dispatch',
      subtitle: 'Vérifier les exceptions — réaffecter ou assigner une livraison',
    },
    import: {
      title: 'Centre d\'importation',
      subtitle: 'Intégration et qualification des données ERP',
    },
    settings: {
      title: 'Paramètres',
      subtitle: 'Configuration du système d\'administration',
    },
    auditLogs: {
      title: 'Journaux d\'audit',
      subtitle: 'Traçabilité complète des actions administratives',
    },
    performance: {
      title: 'Analyse de performance',
      subtitle: 'Analyses et indicateurs de performance opérationnelle',
    },
    notifications: {
      title: 'Notifications',
      subtitle: 'Centre de notifications et alertes',
    },
    reports: {
      title: 'Rapports',
      subtitle: 'Génération et export de rapports opérationnels',
    },
    operations: {
      title: "Vue d'ensemble",
      subtitle: "Supervision en temps réel des opérations et de la flotte",
    },
    routeBuilder: {
      title: 'Créer une tournée',
      subtitle: 'Planification cartographique et optimisation des tournées',
    },
    companies: {
      title: 'Entreprises',
      subtitle: 'Gestion des locataires et configurations ERP',
    },
  },


  // ── Labels statuts (pour affichage) ──────────────────────────────────
  statusLabels: {
    DRAFT: 'Brouillon',
    VALIDATED: 'Validée',
    IN_PROGRESS: 'En cours',
    CLOSED: 'Terminée',
    COMPLETED: 'Terminée',
    CANCELLED: 'Annulé',
    FAILED: 'Échec',
    SLA_BREACH: 'Hors SLA',
    UNSCHEDULED: 'Non planifié',
    SCHEDULED: 'Planifié',
    PENDING: 'En attente',
    ARRIVED: 'Arrivé',
    PICKED_UP: 'Récupéré',
    IN_TRANSIT: 'En route',
    DELIVERED: 'Livré',
    PARTIALLY_DELIVERED: 'Partielle',
    PARTIAL: 'Partielle',
    REMOVED: 'Supprimé',
    REMOVED_REPLANNED: 'Replanifié',
    REMOVED_CANCELLED: 'Annulé (retiré)',
    FAILED_ATTEMPT: 'Tentative échouée',
  } as Record<string, string>,

  // ── Codes d'échec de livraison ────────────────────────────────────────
  failureCodes: {
    CLIENT_ABSENT:   'Client absent',
    REFUSED:         'Refus du client',
    WRONG_ADDRESS:   'Adresse incorrecte',
    DAMAGED:         'Article endommagé',
    OTHER:           'Autre motif',
  } as Record<string, string>,

  // ── Source de la commande ─────────────────────────────────────────────
  sources: {
    APP:    'Application mobile',
    ODOO:   'ERP Odoo',
    MANUAL: 'Saisie manuelle',
    ERP:    'ERP',
  } as Record<string, string>,

  // ── Statut de synchronisation ERP ────────────────────────────────────
  syncStatus: {
    SYNCED:          'Synchronisé',
    PENDING_RETRY:   'Nouvelle tentative en cours',
    PENDING_CANCEL:  'Annulation en cours',
    SYNC_FAILED:     'Échec de synchronisation',
  } as Record<string, string>,

  // ── Priorité de livraison ─────────────────────────────────────────────
  priorities: {
    HIGH:   'Urgent',
    NORMAL: 'Normal',
    LOW:    'Faible',
    URGENT: 'Urgent',
  } as Record<string, string>,

  // ── Auteur d'un événement (timeline) ─────────────────────────────────
  actors: {
    SYSTEM:     'Système',
    AUTO:       'Automatique',
    DRIVER:     'Livreur',
    ADMIN:      'Administrateur',
    DISPATCHER: 'Dispatcheur',
    MANAGER:    'Manager',
    ERP:        'ERP',
    ODOO:       'ERP Odoo',
  } as Record<string, string>,

  // ── Raison de refus / problème article ───────────────────────────────
  itemReasons: {
    CLIENT_ABSENT:    'Client absent',
    CLIENT_REJECTED:  'Refus du client',
    DAMAGED:          'Article endommagé',
    WRONG_ITEM:       'Mauvais article',
    WRONG_ADDRESS:    'Adresse incorrecte',
    POSTPONED:        'Reporté',
    OTHER:            'Autre',
    REFUSED:          'Refusé par le client',
  } as Record<string, string>,

  // ── Résultat d'un article (livraison partielle) ───────────────────────
  outcomes: {
    DELIVERED:           'Livré',
    PARTIAL:             'Partielle',
    PARTIALLY_DELIVERED: 'Partielle',
    REFUSED:             'Refusé',
    DAMAGED:             'Endommagé',
    MISSING:             'Manquant',
    RETURNED:            'Retourné',
    WRONG_ITEM:          'Mauvais article',
    NOT_HOME:            'Client absent',
    EXPIRED:             'Périmé',
    POSTPONED:           'Reporté',
  } as Record<string, string>,

  // ── Statut SLA / délai ────────────────────────────────────────────────
  delayStatus: {
    EARLY:   'En avance',
    ON_TIME: 'Dans les délais',
    LATE:    'En retard',
  } as Record<string, string>,

  // ── Disponibilité chauffeur / véhicule ───────────────────────────────
  availability: {
    driverAvailable: 'Disponible pour affectation',
    driverBusy: 'En tournée active',
    driverOnStandby: 'En veille',
    vehicleAvailable: 'Disponible',
    vehicleBusy: 'Engagé en tournée',
    vehicleOffline: 'Hors service',
  },

  // ── Chargement ───────────────────────────────────────────────────────
  loading: {
    map: 'Chargement de la carte...',
    data: 'Chargement en cours...',
    saving: 'Enregistrement...',
    generic: 'Chargement...',
  },

  // ── notificationsDropdown ─────────────────────────────────────────────
  notificationsDropdown: {
    title: 'Notifications',
    markAllRead: 'Tout lire',
    viewAll: 'Tout voir',
    noNotifications: 'Aucune notification',
    today: "Aujourd'hui",
    thisWeek: 'Cette semaine',
    older: 'Plus tôt',
    viewMore: 'Voir {count} notifications supplémentaires →',
    justNow: "à l'instant",
    minutesAgo: '{minutes} min',
    hoursAgo: '{hours} h',
    daysAgo: '{days} j',
  },

  // ── notificationsPage ─────────────────────────────────────────────────
  notificationsPage: {
    title: 'Flux opérations',
    statUnread: 'non lues',
    statCritical: 'critiques',
    statTotal: 'événements',
    markAllRead: 'Tout marquer lu',
    clearAll: 'Vider',
    eventSingular: 'événement',
    eventPlural: 'événements',
    filters: {
      all: 'Tout',
      unread: 'Non lues',
      critical: 'Critiques',
      warning: 'Alertes',
      info: 'Infos',
    },
    groups: {
      today: "Aujourd'hui",
      week: 'Cette semaine',
      older: 'Plus tôt',
    },
    empty: {
      title: 'Tout est calme',
      subtitleAll: 'La flotte tourne sans accroc. Les événements livraisons, tournées et ERP apparaîtront ici en temps réel.',
      subtitleFiltered: 'Aucun événement ne correspond à ce filtre.',
    },
    time: {
      justNow: "à l'instant",
      minutesAgo: 'il y a {minutes} min',
      hoursAgo: 'il y a {hours} h',
      daysAgo: 'il y a {days} j',
      dateLocale: 'fr-FR',
    },
  },

  // ── globalSearch ──────────────────────────────────────────────────────
  globalSearch: {
    triggerPlaceholder: 'Rechercher ou allez à...',
    inputPlaceholder: 'Tapez pour lancer une recherche...',
    searching: 'Recherche en cours…',
    minChars: 'Saisissez au moins 2 caractères',
    noResults: 'Aucun résultat trouvé',
    groups: {
      recents: 'Récents',
      systemActions: 'Actions Système',
      quickNav: 'Navigation rapide',
      deliveries: 'Livraisons',
      routes: 'Tournées',
      drivers: 'Chauffeurs',
      vehicles: 'Véhicules',
      depots: 'Dépôts',
      zones: 'Zones',
    },
    system: {
      themeTitle: "Changer le thème d'affichage",
      themeDesc: "Bascule entre le mode clair et le mode sombre",
      refreshTitle: 'Actualiser les données',
      refreshDesc: 'Synchronise le tableau de bord avec le serveur',
      driverTitle: 'Ajouter un nouveau chauffeur',
      driverDesc: 'Inviter un livreur sur la plateforme',
    },
    shortcuts: {
      navigate: 'naviguer',
      open: 'ouvrir',
      close: 'fermer',
    },
  },

  // ── Dashboard page ──────────────────────────────────────────────────────
  dashboardPage: {
    syncError: 'Échec de synchronisation',
    kpiTotal: 'Total',
    kpiInTransit: 'En transit',
    kpiDelivered: 'Livrées',
    kpiExceptions: 'Exceptions',
    periodDay: 'Auj.',
    periodWeek: 'Sem.',
    periodMonth: 'Mois',
    periodAll: 'Tout',
    dispatchFlowTitle: 'flux de dispatch',
    plannerButton: 'Planificateur',
    emptyState: 'vide',
    driverPerformanceTitle: 'performance par chauffeur',
    chartLegendTotal: 'Total',
    chartLegendDelivered: 'Livrées',
    serviceQualityTitle: 'qualité de service',
    slaRateLabel: 'taux SLA',
    statsCompleted: 'Complétées',
    statsInProgress: 'En cours',
    statsExceptions: 'Exceptions',
    unknownClient: 'Client inconnu',
    moreDeliveries: '+{count} autres',
    // Hero KPIs
    heroSlaRate: 'Taux SLA',
    heroDeliveredOf: '{delivered} sur {total}',
    heroActiveRoutes: 'Tournées actives',
    heroDriversOnline: 'Livreurs en ligne',
    // Needs attention feed
    needsAttention: 'Nécessite une attention',
    needsAttentionOverdue: 'En retard',
    needsAttentionUnassigned: 'Non assignées',
    needsAttentionFailed: 'Échouées',
    needsAttentionCritical: 'Alertes critiques',
    needsAttentionEmpty: 'Tout est sous contrôle',
    needsAttentionViewAll: 'Voir tout',
    // Driver availability
    driverAvailability: 'Disponibilité livreurs',
    driverOnline: 'En service',
    driverOnBreak: 'En pause',
    driverOffline: 'Hors ligne',
    // Today's progress
    todayProgress: 'Progression du jour',
    progressPending: 'En attente',
    progressDelivered: 'Livrées',
    progressInTransit: 'En transit',
    progressFailed: 'Échouées',
    // Quick actions
    quickActions: 'Actions rapides',
    actionGoToDispatch: 'Centre de dispatch',
    actionGoToPlanner: 'Planificateur',
    actionGoToRoutes: 'Tournées',
    actionGoToDeliveries: 'Livraisons',
    // KPI card
    kpiActiveRoutes: 'Tournées actives',
    kpiDriversOnline: 'Livreurs en ligne',
    kpiSlaRate: 'Taux SLA',
    kpiDeliveredOf: 'sur',
  },

  // ── Operations Page ─────────────────────────────────────────────────────
  operationsPage: {
    // Header
    subtitle: 'Suivi des tournées',
    title: 'Centre de pilotage',
    // Tabs
    tabToday: "Aujourd'hui",
    tabWeek: 'Semaine',
    // KPIs
    kpiActiveRoutes: 'Tournées Actives',
    kpiFieldDrivers: 'Livreurs Terrain',
    kpiCompletedStops: 'Arrêts Complétés',
    kpiFailures: 'Échecs / Alertes',
    // Section: To Start
    sectionStart: 'À Démarrer',
    noRoutesWaiting: 'Aucune tournée en attente',
    // Section: Watchpoints
    sectionWatchpoints: 'Points de Vigilance',
    allUnderControl: 'Tout est sous contrôle',
    // Performance analysis
    sectionPerformance: 'Analyse de Performance',
    labelSuccess: 'Réussis',
    labelFailures: 'Échecs',
    labelOngoing: 'En cours',
    // Routes table
    tableTodayRoutes: 'Tournées du jour',
    tableRoute: 'Tournée',
    tableDriver: 'Livreur',
    tableStatus: 'Statut',
    tableProgress: 'Progression',
    noRoutes: 'Aucune tournée',
    // Week tab
    thisWeek: 'Cette semaine',
    routesCount: 'tournées',
    completion: 'complétion',
    weekInProgress: 'en cours',
    weekClosed: 'fermées',
    weekTotal: 'au total',
    // Day and month names
    dayNames: ['Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi', 'Dimanche'],
    monthNames: ['jan', 'fév', 'mar', 'avr', 'mai', 'jun', 'jul', 'aoû', 'sep', 'oct', 'nov', 'déc'],
  },

  // ── Dispatch Desk Page ─────────────────────────────────────────────────────
  dispatchDeskPage: {
    // Mobile tabs
    tabFilters: 'Filtres',
    tabDispatch: 'Dispatch',
    // Filter panel
    filterToggleReduce: 'Réduire les filtres',
    filterToggleShow: 'Afficher les filtres',
    filterQuickSearch: 'Recherche rapide',
    filterQuickSearchPlaceholder: 'Réf, Client, Chauffeur...',
    // Period filter
    filterPeriod: 'Période',
    periodDay: 'Auj.',
    periodWeek: 'Sem.',
    periodMonth: 'Mois',
    periodAll: 'Tout',
    periodCustom: 'Plage',
    dateFrom: 'Du',
    dateTo: 'Au',
    // Other filters
    filterDriver: 'Chauffeur',
    filterDriverPlaceholder: 'Tous les chauffeurs',
    filterDriverNoFilter: 'Sans filtre chauffeur',
    filterZone: 'Zone',
    filterZonePlaceholder: 'Toutes les zones',
    filterZoneGlobal: 'Secteur global',
    filterRoute: 'Tournée',
    filterRoutePlaceholder: 'Toutes',
    filterStatus: 'Statut',
    filterStatusPlaceholder: 'Tous les statuts',
    filterStatusAll: 'Tous les statuts',
    filterStatusUnscheduled: 'Non planifié',
    filterStatusScheduled: 'Planifié',
    filterStatusPickedUp: 'Récupéré',
    filterStatusInTransit: 'En route',
    filterStatusDelivered: 'Livré',
    filterStatusPartial: 'Partielle',
    filterStatusCancelled: 'Annulé',
    filterStatusFailed: 'Échec',
    // Buttons
    buttonLoading: 'Chargement…',
    buttonRefresh: 'Actualiser',
    // Active drivers section
    activeDriversLabel: 'En service',
    driverOnline: 'En service',
    driverOnBreak: 'En pause',
    driverOffline: 'Hors ligne',
    // KPI strip
    kpiCritical: 'Critiques',
    kpiUnassigned: 'Non assignés',
    kpiInTransit: 'En transit',
    kpiFailed: 'Échoués',
    // Tabs
    tabAssign: 'Assignation',
    tabAction: 'Action requise',
    tabFailed: 'Échouées',
    tabMissingGps: 'GPS manquant',
    tabHandoff: 'Passations',
    // Batch action bar
    batchCount: '{count} sélectionnée{plural}',
    batchMixedWarning: 'Sélection mixte — choisissez un seul type',
    batchAssign: 'Assigner ({count})',
    batchReassign: 'Réassigner ({count})',
    batchCancel: 'Annuler',
    kpiUpdated: 'Mis à jour',
    cardCreated: 'Créé depuis',
    handoffEmpty: 'Aucune passation en cours',
    handoffStateRequested: 'En attente du code',
    handoffStateInProgress: 'Code émis · attente du scan',
    handoffStateOverdue: 'En retard',
    handoffCancelButton: 'Annuler',
    handoffCancelTitle: 'Annuler la passation',
    handoffCancelDescription: 'La passation sera annulée. Le colis reste chez le chauffeur expéditeur et devra être replanifié.',
    handoffCancelReasonLabel: 'Motif (optionnel)',
    handoffCancelConfirm: 'Annuler la passation',
    // New alerts banner
    newAlertSingular: 'nouvelle alerte',
    newAlertPlural: 'nouvelles alertes',
    newAlertBannerRefresh: 'Actualiser',
    // Toast messages
    errorLoadingAlerts: 'Impossible de charger les alertes',
    errorCannotReassign: 'Cette livraison ne peut pas être réassignée dans son état actuel',
    errorCannotReplan: 'Cette livraison ne peut pas être reprogrammée dans son état actuel',
    successReplanned: 'Livraison remise en attente de planification',
    errorReplan: 'Impossible de reprogrammer',
    errorNoteRequired: 'Veuillez ajouter une note avant de confirmer',
    successCancelled: 'Livraison annulée',
    errorCancel: "Impossible d'annuler",
    successReturnConfirmed: 'Retour confirmé',
    errorReturnConfirm: 'Impossible de confirmer le retour',
    // Table headers
    tableHeaderOrder: 'Commande',
    tableHeaderStatus: 'Statut',
    tableHeaderClient: 'Client',
    tableHeaderZone: 'Zone',
    tableHeaderDriver: 'Chauffeur',
    tableHeaderMotif: 'Motif',
    tableHeaderAlert: 'Alerte',
    tableHeaderCoordinates: 'Coordonnées',
    // Table content
    noActionRequired: 'Aucune action requise',
    noOrdersFound: 'Aucune commande trouvée',
    unassigned: 'Non assigné',
    missingGps: 'GPS manquant',
    fixGps: 'Corriger →',
    // Modal titles
    returnTitle: 'Confirmer le retour au dépôt',
    returnDescription: 'Le colis a bien été ramené au dépôt. Il sera remis en liste d\'attente pour être planifié à nouveau.',
    returnNoteLabel: 'Note de retour (facultatif)',
    returnConfirmLabel: 'Confirmer le retour',
    cancelTitle: 'Annuler définitivement la livraison',
    cancelDescription: 'Cette action est irréversible. La livraison sera marquée comme annulée et ne pourra plus être planifiée.',
    cancelReasonLabel: 'Raison de l\'annulation (obligatoire)',
    cancelConfirmLabel: 'Annuler définitivement',
    keepLabel: 'Garder',
    // Detail modal
    detailTitle: 'Détail',
    orderLabel: 'Commande',
    openLink: 'Ouvrir →',
    driverLabel: 'Chauffeur',
    replanButton: 'Reprogrammer',
    reassignButton: 'Réassigner',
    // Tooltips
    reassignTooltip: 'Réassigner',
    assignDriverTooltip: 'Assigner un chauffeur',
    replanTooltip: 'Reprogrammer',
    callClientTooltip: 'Appeler le client',
    callDriverTooltip: 'Appeler le chauffeur',
    // Additional descriptions
    reasonLabel: 'Motif',
    noComment: 'Aucun commentaire',
    reprogrammationLabel: 'Reprogrammation',
    byLabel: 'par',
    scheduledDateLabel: 'Date prévue',
    // Motif labels (formatMotif)
    motifSlaUnscheduled: 'Commande non planifiée',
    motifSlaScheduled: 'Bloqué au dépôt',
    motifSlaPickup: 'Ramassage en retard',
    motifSlaInTransit: 'Livraison en retard',
    motifScheduledMonitoring: 'Arrêt précédent en cours',
    motifClientAbsent: 'Client absent',
    motifRefused: 'Refus de réception',
    motifWrongAddress: 'Adresse incorrecte',
    motifDamaged: 'Colis endommagé',
    motifOther: 'Autre motif',
    motifPartialDelivery: 'Livraison partielle',
    motifFailed: 'Livraison échouée',
    motifCancelled: 'Livraison annulée',
    motifUnscheduled: 'Non planifié',
    motifScheduled: 'Planifié',
    motifPickedUp: 'Ramassé',
    motifInTransit: 'En route',
    motifDelivered: 'Livré',
    motifPartiallyDelivered: 'Livré partiellement',
    motifUnknown: 'Incident inconnu',
    motifSlaUnscheduledLate: 'Retard (Non Planifié)',
    motifSlaUnscheduledToday: 'Planifié Aujourd\'hui',
    // Time elapsed (formatElapsed)
    timeJustNow: 'à l\'instant',
    timeMinutes: '{diff} min',
    timeHours: '{h}h{mm}',
    timeDays: '{d}j',
    // Comments (formatComment)
    commentSlaUnscheduled: 'En attente d\'une tournée depuis {time}',
    commentSlaScheduled: 'Colis au dépôt mais non récupéré depuis {time}',
    commentSlaPickup: 'Chauffeur pas encore arrivé chez le client · En attente depuis {time}',
    commentSlaInTransit: 'En route mais accuse du retard · Signalé il y a {time}',
    commentScheduledMonitoring: 'Chauffeur finalise un arrêt précédent · Sera traité ensuite',
    commentClientAbsent: 'Le client était absent lors de la tentative · il y a {time}',
    commentRefused: 'Le client a refusé la livraison · il y a {time}',
    commentWrongAddress: 'Adresse de livraison introuvable ou incorrecte · il y a {time}',
    commentDamaged: 'Colis signalé endommagé · il y a {time}',
    commentOther: 'Échec sans motif précis · il y a {time}',
    commentPartial: 'Livraison partielle signalée il y a {time}',
    commentFailed: 'Échec de livraison · il y a {time}',
    commentCancelled: 'Annulée il y a {time}',
    commentDefault: 'Signalé il y a {time}',
    commentSlaUnscheduledLate: 'En retard · {time}',
    commentSlaUnscheduledToday: 'Planifié pour aujourd\'hui',
    // Suggestions (formatSuggestion)
    suggestionSlaUnscheduled: '→ À planifier dans une tournée',
    suggestionScheduledMonitoring: '→ Surveiller — aucune action immédiate',
    suggestionWrongAddress: '→ Corriger l\'adresse dans la fiche commande',
    suggestionOther: '→ Vérifier le commentaire chauffeur',
    suggestionSlaUnscheduledLate: '→ Assigner une tournée d\'urgence',
    // ReplanModal
    replanModalTitleReplan: 'Remettre en attente de planification',
    replanModalTitleReassign: 'Réassigner la livraison',
    replanModalLabelOrder: 'Commande',
    replanModalLabelProblem: 'Problème',
    replanModalLabelClient: 'Client',
    replanModalWhatWillHappen: 'Que va-t-il se passer ?',
    replanModalDescription: 'Cette livraison sera retirée de la tournée actuelle{routeName} et remise en liste d\'attente. Un planificateur devra ensuite la réaffecter à une nouvelle tournée.',
    replanModalScheduledLabel: 'Nouvelle date planifiée',
    replanModalScheduledHint: 'Remplace la date ERP périmée — le SLA d\'affectation se base sur cette date.',
    replanModalNoteLabel: 'Note (obligatoire)',
    replanModalNoteHint: 'Expliquez brièvement pourquoi vous effectuez cette action',
    replanModalNotePlaceholder: 'Ex : Client absent, adresse incorrecte, chauffeur indisponible…',
    // ActionRow button tooltips
    buttonReassign: 'Réassigner',
    buttonAssign: 'Assigner un chauffeur',
    buttonReplan: 'Reprogrammer',
    buttonCallClient: 'Appeler le client',
    buttonContactClient: 'Contacter le client',
    buttonCallDriver: 'Appeler le chauffeur',
    buttonReturnToDepot: 'Retour au dépôt',
    // ActionRow labels
    unassignedLabel: 'Non assigné',
    incidentLabel: 'Incident',
    criticalLabel: 'Critique',
    reportedLabel: 'Signalé le',
    updatedLabel: 'Mis à jour',
    clientLabel: 'Client',
    routeLabel: 'Tournée',
    routeInProgress: 'En cours',
    routeValidated: 'Validée',
    routeDraft: 'Brouillon',
    noRouteAssigned: 'Aucune tournée assignée',
    pageTitleFallback: 'Dispatch',
  },

  // ── ReassignDrawer ───────────────────────────────────────────────────────
  reassignDrawer: {
    // Step labels
    stepDriver: 'Chauffeur',
    stepRoute: 'Tournée',
    stepConfigure: 'Configurer',
    // Route status
    routeDraft: 'Brouillon',
    routeValidated: 'Validée',
    routeInProgress: 'En cours',
    routeClosed: 'Clôturée',
    routeCancelled: 'Annulée',
    // Drawer title
    assignTitle: 'Assigner une livraison',
    assignBatchTitle: '{count} assignations',
    reassignTitle: 'Réaffecter',
    batchTitle: '{count} livraisons',
    // Buttons
    backButton: 'Retour',
    changeDriver: 'Changer',
    confirmReassign: 'Confirmer la réaffectation',
    confirmBatch: 'Confirmer ({count} livraisons)',
    // Step 1: Driver selection
    searchPlaceholder: 'Rechercher un chauffeur…',
    onlineWithRoute: 'En service — avec tournée',
    onlineNoRoute: 'En service — sans tournée',
    autoRouteCreated: 'Tournée créée automatiquement en brouillon',
    onBreak: 'En pause',
    showOffline: 'Afficher',
    hideOffline: 'Masquer',
    offlineLabel: 'hors service',
    offlineWarning: 'Les chauffeurs hors service recevront l\'assignation mais ne sont pas en activité.',
    noDriver: 'Aucun chauffeur trouvé',
    // Stop info
    stopFree: 'libre',
    loadingRoutes: 'Chargement…',
    noRoutes: 'Aucune tournée',
    // Step 2: Route selection
    loadingRoutesStep2: 'Chargement des tournées…',
    noActiveRoutes: 'Aucune tournée active (30 prochains jours)',
    noActiveRoutesDesc: 'Ce chauffeur n\'a pas de tournée planifiée. Créez-en une depuis la page Tournées.',
    routeCount: '{count} tournée{plural}',
    // Step 3: Configure
    timeWindowLabel: 'Fenêtre de livraison',
    timeWindowStart: 'Début',
    timeWindowEnd: 'Fin',
    timeWindowError: 'Fin avant début',
    timeWindowErrorDesc: 'L\'heure de fin doit être postérieure à l\'heure de début',
    noteForDriver: 'Note pour le chauffeur',
    noteOptional: '(Optionnelle)',
    noteRequired: '*',
    notePlaceholder: 'Explication de la réaffectation…',
    noteInternalPlaceholder: 'Note interne (optionnelle)…',
  },

  reassignCommandOverlay: {
    deliveryLabel: 'Livraison',
    title: 'Réassigner l\'entité',
    driverTab: 'Chauffeur',
    routeTab: 'Tournée',
    searchDriver: 'Rechercher un chauffeur…',
    searchRoute: 'Rechercher une tournée…',
    unnamed: 'Chauffeur sans nom',
    activeRoute: 'Tournée active',
    stops: 'Arrêts',
    noResults: 'Aucune entité trouvée',
    noDriver: 'Aucun chauffeur assigné',
    operationParams: 'Paramètres opérationnels',
    orderLabel: 'Ordre de passage de l\'arrêt',
    timeStart: 'Heure de début',
    timeEnd: 'Heure de fin',
    noteLabel: 'Note',
    notePlaceholder: 'Expliquez la réaffectation…',
    capacityTarget: 'Utilisation de la capacité',
    forceWarning: 'Forcer l\'affectation (ignorer l\'alerte)',
    cancelBtn: 'Annuler',
    confirmBtn: 'Confirmer',
  },

  // ── Delivery Detail Page ──────────────────────────────────────────────────
  deliveryPage: {
    notFound: 'Livraison introuvable',
    cancelled: 'Annulée',
    loadingFile: 'Chargement du dossier...',
    returnButton: 'Retour',
    // Section: Client
    sectionClient: 'Client',
    labelName: 'Nom',
    labelPhone: 'Téléphone',
    labelAddress: 'Adresse',
    labelCity: 'Ville',
    labelPostalCode: 'Code postal',
    labelZone: 'Zone',
    // Section: Order
    sectionOrder: 'Commande',
    labelReference: 'Référence',
    labelInternalId: 'ID interne',
    labelTotalWeight: 'Poids total',
    labelAmount: 'Montant',
    labelSource: 'Source',
    labelSyncErp: 'Sync ERP',
    labelCreatedAt: 'Créée le',
    labelUpdatedAt: 'Mise à jour',
    // Section: Driver & Route
    sectionDriverRoute: 'Chauffeur & Tournée',
    sectionFulfillment: 'Engagement & BL',
    scheduledLabel: 'Date promise',
    blNumberLabel: 'N° bon de livraison',
    sourceDepotLabel: 'Dépôt source',
    viewBL: 'Voir le bon de livraison',
    rescheduledBadge: 'Reprogrammé',
    rescheduledTooltip: 'Date reprogrammée par le dispatcher (remplace la date ERP)',
    labelDriver: 'Chauffeur',
    labelRoute: 'Tournée',
    seeRoute: 'Voir la tournée',
    // Section: Items
    sectionItems: 'Articles',
    itemsCount: 'Articles · {count} ligne{plural}',
    // Table headers
    tableDesignation: 'Désignation',
    tableSku: 'SKU',
    tableQty: 'Qté',
    tableQtyDone: 'Qté livrée',
    tableUnitPrice: 'P.U.',
    tableTotal: 'Total',
    // Section: Timeline
    sectionTimeline: 'Suivi du statut',
    noHistory: 'Aucun historique disponible.',
    // Section: Proof of Delivery
    sectionProof: 'Preuve de livraison',
    noPhoto: 'Pas de photo',
    noBL: 'Pas de BL signé',
    refresh: 'Actualiser',
    unknownClient: 'Client inconnu',
    byLabel: 'par',
    reasonLabel: 'Raison:',
    commentLabel: 'Commentaire',
    collectedAtLabel: 'Collectée le',
    coordinatesLabel: 'Coordonnées',
    proofPhoto: 'Photo de livraison',
    proofSignedBL: 'BL Signé',
  },

  // ── Breadcrumbs ──────────────────────────────────────────────────────────
  breadcrumbs: {
    home: 'Accueil',
    separator: '/',
  },

  // ── TopNav & Theme ──────────────────────────────────────────────────────
  topNav: {
    toggleSidebar: 'Toggle sidebar',
    myAccount: 'Mon compte',
    logout: 'Déconnexion',
    lightMode: 'Mode clair',
    darkMode: 'Mode sombre',
    enableLightMode: 'Activer le mode clair',
    enableDarkMode: 'Activer le mode sombre',
    user: 'Utilisateur',
  },

  // ── Routes Detail Page ──────────────────────────────────────────────────
  routeDetailPage: {
    labelOrder: 'Commande',
    labelErpRef: 'Réf. ERP',
    labelWeight: 'Poids',
    labelQty: 'Qté',
    labelSource: 'Source',
    labelTimeWindow: 'Fenêtre horaire',
    labelInstructions: 'Instructions',
    labelNotes: 'Note',
    pickupLabel: 'Dépôt',
    pickupTitle: 'Chargement — Dépôt {depot}',
    pickupLoadCount: '{count} colis à charger',
    pickupArrival: 'Arrivée prévue',
    pickupCompleted: 'Chargement confirmé',
    labelDepot: 'Dépôt',
    labelDeparture: 'Départ',
    noDepot: 'Aucun dépôt',
    labelLifecycle: 'Cycle de vie',
    statusCreated: 'Créée',
    statusValidated: 'Validée',
    statusStarted: 'Démarrée',
    statusClosed: 'Clôturée',
    labelStopsSequence: 'Séquence des arrêts',
    tabDetails: 'Détails',
    tabHistory: 'Historique',
    tabProof: 'Preuve',
    labelArticles: 'Articles',
    tableArticle: 'Article',
    tableOrdered: 'Commandé',
    tableDelivered: 'Livré',
    tableStatus: 'Statut',
    tableUnitPrice: 'Prix unit.',
    labelTotal: 'Total',
    createBackorder: 'Créer backorder',
    loading: 'En cours...',
    noPodAvailable: 'Preuve de livraison indisponible',
    signedBL: 'BL Signé',
    photoPod: 'Photo POD',
    photo: 'Photo',
    deliveryNote: 'Bon de livraison',
    buttonTimeWindow: 'Fenêtre',
    buttonRemove: 'Retirer',
    buttonCancel: 'Annuler',
    modalCancelStopTitle: 'Annuler ce stop',
    modalCancelStopDesc: 'Annuler',
    modalRemoveStopTitle: 'Retirer ce stop',
    modalRemoveStopDesc: 'Retirer',
    modalCancel: 'Annuler',
    modalRemove: 'Retirer',
    modalClose: 'Fermer',
    modalSave: 'Enregistrer',
    labelStart: 'Début',
    labelEnd: 'Fin',
    dataNotLoaded: 'Données non chargées',
    generatingBL: 'Génération du BL en cours...',
    blDownloaded: 'BL téléchargé',
    blGenerationError: 'Erreur génération BL',
    backorderCreated: 'Backorder créé',
    backorderError: 'Erreur backorder',
    stopCancelled: 'Stop annulé',
    stopCancelError: "Échec de l'annulation du stop",
    stopRemoved: 'Stop retiré',
    stopRemoveError: 'Échec de la suppression du stop',
    windowUpdated: 'Fenêtre horaire mise à jour',
    updateError: 'Échec de la mise à jour',
    overlapWarning: '⚠️ Ce créneau chevauche d\'autres arrêts sur la tournée.',
    routeNotFound: 'Tournée introuvable.',
    backToRoutes: 'Retour aux tournées',
    by: 'par',
    tabMap: 'Carte',
    tabStops: 'Arrêts',
    dispatchActionRecorded: 'Action de dispatch enregistrée.',
    deliveryReplanned: 'Livraison remise en file de planification.',
    deliveryReassigned: 'Livraison réaffectée à un autre chauffeur.',
    reason: 'Motif',
    reasonPlaceholder: 'Expliquez pourquoi vous retirez ce stop...',
    refERP: 'ERP',
    refDelivery: 'LIV',
    unitKg: 'kg',
    unitMin: 'min',
    back: 'Retour',
    breadcrumbDetail: 'Détail',
    optimized: 'Optimisée',
    refresh: 'Actualiser',
    labelDriver: 'Chauffeur',
    notAssigned: 'Non assigné',
    labelProgress: 'Progression',
    failed: 'échoué(s)',
    stops: 'arrêts',
    labelCumulativeDelay: 'Retard Cumulé',
    departure: 'Départ',
    labelPunctuality: 'Ponctualité',
    withinWindow: 'dans fenêtre',
    labelDistance: 'Distance',
    labelVehicle: 'Véhicule',
    labelTotalLoad: 'Charge totale',
    capacity: 'cap.',
    buttonRemoveRoute: 'Retirer de la tournée',
    tooltipRemoveStop: 'Retirer ce stop de la route — la livraison retournera au pool non planifié pour redéploiement. Le chauffeur sera notifié immédiatement.',
    tooltipEditWindow: 'Modifier la fenêtre horaire de livraison pour cet arrêt. Disponible uniquement pour les tournées validées avec des arrêts en attente ou planifiés.',
  },

  // ── Import Page ────────────────────────────────────────────────────────
  importPage: {
    // Page structure
    pageSubtitle: 'Flux de données',
    pageTitle: 'Importation',
    pageTitleBrand: 'ERP',

    // Mobile tabs
    tabFilters: 'Filtres',
    tabOrders: 'Commandes',

    // Search & sync
    searchPlaceholder: 'Réf., client...',
    syncButton: 'Synchroniser ERP',

    // Filter pills / Tabs
    pillAll: 'Toutes les commandes',
    pillReady: 'À importer',
    pillDone: 'Déjà importées',

    // Table headers
    headerReference: 'Bon de Livraison / Réf ERP',
    headerCustomer: 'Client',
    headerDestination: 'Destination',
    headerAmount: 'Montant',
    headerStatus: 'Statut',
    headerActions: 'Actions',

    // Table content
    emptyState: 'Aucune commande en attente',
    statusSynced: 'Synchronisé',
    statusReady: 'Prêt à l\'import',
    tooltipDetails: 'Détails',
    syncErpButton: 'Synchroniser ERP',
    buttonView: 'Voir',
    buttonConfirm: 'Confirmer',
    buttonImport: 'Importer',

    // Bulk actions
    selectedMessage: 'commande{plural} sélectionnée{plural}',
    deselect: 'Désélectionner',
    bulkImport: 'Importer ({count})',

    // Pagination
    showing: 'Affichage de {showing} sur {total} commandes',

    // Preview drawer
    drawerTitle: 'Validation Pré-Importation',
    loadingError: 'Échec du chargement',

    // Preview section labels
    previewRefERP: 'Référence ERP',
    previewCustomer: 'Client Final',
    previewLocation: 'Point de Livraison',

    // Metrics
    metricsArticles: 'Articles',
    metricsWeight: 'Poids',
    metricsTotal: 'Total',

    // Items list
    itemsListTitle: 'Contenu de la Commande',
    itemsColArticle: 'Article',
    itemsColQty: 'Qté',
    itemsColPrice: 'Prix',

    // Warnings
    backorderWarning: 'Livraison reliquat détectée (ID: {backorderId})',

    // Action buttons
    buttonViewDelivery: 'Voir la Livraison',
    buttonImportFlow: 'Importer dans le Flux',

    // Last sync
    lastSync: 'Dernière synchronisation :',
  },

  // ── Route Builder Page ─────────────────────────────────────────────────
  routeBuilderPage: {
    // Multi-depot (slice 5)
    pickupTitle: 'Charger {count} colis — Dépôt {depot}',
    pickListLabel: 'Liste de chargement',
    pickupDepotPopup: 'Chargement — {count} colis · {depot}',
    precedenceViolation: 'Une livraison ne peut pas précéder le chargement de son dépôt',
    unmappedDepotChip: 'Dépôt non synchronisé',
    sourceDepotLabel: 'Dépôt source',
    pickupLabel: 'Dépôt',
    // Loading
    loadingMap: 'Chargement Carte...',

    // KPI labels
    kpiScheduled: 'Programmées',
    kpiUnscheduled: 'Non programmées',
    kpiTotal: 'Total',
    kpiRoutes: 'Tournées',

    // Date filter
    buttonToday: 'Aujourd\'hui',
    tooltipTodayRoutes: 'Afficher les tournées d\'aujourd\'hui',

    // Toggle buttons
    buttonExpand: 'Déployer',
    buttonCollapse: 'Réduire',

    // Sidebar (RouteSidebar)
    noRoutesForDate: 'Aucune tournée pour cette date',
    lockRoute: 'Verrouiller la tournée',
    unlockRoute: 'Déverrouiller la tournée',
    selectForBatchOptimize: 'Sélectionner pour optimisation en lot',
    selectAll: 'Tout sélectionner',
    deselectAll: 'Désélectionner',
    newRoute: 'Nouvelle tournée',

    // Modals
    createRouteTitle: 'Créer un itinéraire',
    settingsTitle: 'Paramètres de la tournée',
    settingsSubtitlePrefix: 'Configuration',
    settingsWarning: 'Les horaires seront recalculés après sauvegarde.',
    routeNameReadOnly: 'Nom (lecture seule)',
    deleteConfirmTitle: 'Action irréversible',
    deleteDraftModalTitle: 'Supprimer le brouillon ?',
    deleteDraftModalBody: 'Cette action est irréversible. Toutes les commandes de cet arrêt redeviendront non planifiées.',
    validateTitle: 'Validation de l\'itinéraire',
    routeNameLabel: 'Nom de la tournée',
    operationDateLabel: 'Date d\'opération',
    assignedDriverLabel: 'Chauffeur assigné',
    vehicleLabel: 'Véhicule',
    departureDepotLabel: 'Dépôt de départ',
    logisticsDepotLabel: 'Dépôt logistique',
    selectDepot: 'Sélectionner le dépôt…',
    selectPlaceholder: 'Sélectionner…',
    selectRoutePlaceholder: 'Choisir une tournée…',
    deleteRouteButton: 'Supprimer la tournée',
    vehicleBusy: ' — Occupé',

    // Search & filters
    searchClientRefId: 'Rechercher client, ID, référence…',
    searchPlaceholder: 'Rechercher…',
    noResults: 'Aucun résultat',
    clearSelection: 'Effacer la sélection',
    selectedStops: '{count} arrêt{plural} sélectionné{plural}',

    // Orders table
    emptyOrdersState: 'Aucune commande à importer',
    quickViewAll: 'Toutes',
    quickViewToday: 'Aujourd\'hui',
    quickViewThisWeek: 'Cette semaine',

    // ValidationModal & Modals general
    finalReviewSubtitle: 'Revue finale',
    chronoErrors: 'Erreurs de chronologie',
    readyForValidation: 'Prêt pour validation',
    noStopsDetected: 'Aucun arrêt détecté.',
    headerIndex: '#',
    headerClientDestination: 'Client & destination',
    headerStart: 'Début',
    headerEnd: 'Fin',
    headerStatus: 'État',

    // StopsPanel / General Panel
    selectRoutePromptTitle: 'Sélectionnez une tournée',
    selectRoutePromptDesc: 'Choisissez une tournée à gauche pour gérer ses arrêts.',
    validatedBadge: 'Validée',
    draftBadge: 'Brouillon',
    stopsCountLabelSingular: '{count} arrêt',
    stopsCountLabelPlural: '{count} arrêts',
    payloadLabel: 'Charge utile',
    overloadLabel: 'Surcharge: +{amount} kg',
    chronoConflictWarning: 'Conflits dans la chronologie des fenêtres horaires.',
    finalizeValidateButton: 'Finaliser & valider',

    // OrdersTable
    headerIdErp: 'ID ERP',
    headerIdAsm: 'ID ASM',
    headerClient: 'Client',
    headerArticles: 'Articles',
    headerWeight: 'Charge',
    headerDate: 'Date',
    articlesCountSingular: '{count} article',
    articlesCountPlural: '{count} articles',
    assignButton: 'Assigner ({count})',

    // RoutesTable
    headerRoute: 'Tournée',
    headerDriver: 'Chauffeur',
    headerVehicle: 'Véhicule',
    headerStops: 'Arrêts',
    headerDistance: 'Distance',
    headerDuration: 'Durée',

    // TimelineGantt
    emptyTimelineGantt: 'Aucune tournée à afficher dans la timeline',
    noDriver: 'Sans chauffeur',

    // ActionBar
    actionBarSettingsTooltip: 'Paramètres de la tournée',
    actionBarSettingsLabel: 'Paramètres',
    optimizingProgress: 'Optimisation...',
    optimize: 'Optimiser',
    validate: 'Valider',
    savingProgress: 'Sauvegarde...',
    save: 'Sauvegarder',

    // StopsList
    dragDropPrompt: 'Glissez-déposez des commandes pour commencer la planification.',
    deselectButton: 'Désélectionner',
    removeButton: 'Retirer ({count})',

    // OptimizePreview
    optimizePreviewTitle: 'Aperçu d\'optimisation',
    gainLabel: 'Gain',
    suggestedRouteLabel: 'Itinéraire suggéré par OSRM',
    applyButton: 'Appliquer',
    departureTimeLabel: 'Heure de départ',
    durationLabel: 'Durée',
    noSuggestionLabel: 'Aucune suggestion à afficher',
    sincePreviousStopLabel: 'depuis l\'arrêt précédent',

    // Map & Overlays
    mapLayerStreet: 'Plan',
    mapLayerHot: 'HOT',
    mapLayerSatellite: 'Satellite',
    layerDepots: 'Dépôts',
    layerTraces: 'Tracés',
    activeZoneLabel: 'Zone active',
    activeZoneNone: 'Aucune zone',
    tabOrders: 'Commandes',
    tabRoutes: 'Tournées',
    tabTimeline: 'Timeline',
    dragCardOrder: 'Commande',
    dragCardOrders: '{count} commandes',
    dragCardArticles: 'Articles',
    dragCardOtherArticles: '+{count} autres articles',
    dragCardArticleCount: '{count} article{plural}',
    dragCardDropPrompt: 'Déposer sur une tournée',
    dragCardScrollPrompt: 'molette pour défiler',
    mapSearchPlaceholder: 'Rechercher un lieu…',
    pageTitle: 'Planification des tournées',
    breadcrumbDashboard: 'Dashboard',
    selectedRoutes: '{count} sélectionnée{plural}',
    eligibleRoutes: '({count} éligibles)',
    lockOrLessStopsIgnored: 'Les tournées verrouillées ou avec moins de 2 arrêts sont ignorées.',
    batchOptimizeButton: 'Optimiser',
    clearSelectionTooltip: 'Effacer la sélection',

    // Toast messages
    toastLoadFailed: 'Échec du chargement des données',
    toastRouteNameRequired: 'Le nom de la tournée est requis',
    toastDateRequired: 'La date de la tournée est requise',
    toastDriverRequired: 'Veuillez sélectionner un chauffeur',
    toastDepotRequired: 'Veuillez sélectionner un dépôt',
    toastRouteCreated: 'Tournée créée',
    toastOrderNotPinned: 'La commande {order} n\'est pas épinglée — épinglez-la d\'abord sur la carte avant d\'assigner.',
    toastSelectRouteFirst: 'Sélectionnez une tournée d\'abord',
    toastNoOrderSelected: 'Aucune commande sélectionnée',
    toastOrderAssigned: 'Commande {order} assignée',
    toastOrdersAssigned: '{count} commandes assignées',
    toastOsrmSuggested: 'Suggestion OSRM générée : ordre, trajet et ETA prêts à visualiser.',
    toastOsrmFailed: 'Échec de l\'optimisation backend OSRM',
    toastRouteLocked: 'Tournée verrouillée',
    toastRouteUnlocked: 'Tournée déverrouillée',
    toastLockFailed: 'Échec du verrouillage',
    toastUnlockFailed: 'Échec du déverrouillage',
    toastNoEligibleRoutes: 'Aucune tournée éligible (verrouillée ou < 2 arrêts).',
    toastBatchOptimizeOk: '{count} tournée{plural} optimisée{plural}.',
    toastBatchOptimizePartial: '{ok} optimisée{plural}, {ko} en échec.',
    toastBatchOptimizeFailed: 'Échec de l\'optimisation en lot.',
    toastOsrmApplied: 'Optimisation OSRM et fenêtres horaires appliquées',
    toastStopRemoved: 'Arrêt retiré de la tournée',
    toastStopsRemoved: '{count} arrêts retirés',
    toastTimeWindowRequired: 'Tous les arrêts doivent avoir une fenêtre horaire (début et fin)',
    toastTimeWindowEndBeforeStart: 'L\'heure de fin doit être après l\'heure de début',
    toastTimeWindowChronoError: 'Erreur de chronologie : chaque arrêt doit commencer après la fin du précédent',
    toastTimeWindowsSaved: 'Fenêtres horaires enregistrées',
    toastSettingsDateRequired: 'La date est requise',
    toastSettingsSaved: 'Paramètres de la tournée enregistrés',
    toastItineraryClear: 'Itinéraire supprimé',
    toastRouteValidated: 'Tournée validée',
    toastRouteNotFound: 'Tournée introuvable',
    toastReorgFailed: 'Réorganisation échouée, annulée',
    toastStopTransferred: 'Arrêt transféré',
    toastTransferFailed: 'Transfert échoué, annulé',

    // Route details page
    routeNotFound: 'Tournée introuvable.',
    backToRoutes: 'Retour aux tournées',
    tabMap: 'Carte',
    tabStops: 'Arrêts',
    depotLabel: 'Dépôt',
    departureLabel: 'Départ',
    noDepot: 'Aucun dépôt',
    lifecycleTitle: 'Cycle de vie',
    statusCreated: 'Créée',
    statusValidated: 'Validée',
    statusStarted: 'Démarrée',
    statusClosed: 'Clôturée',
    stopSequence: 'Séquence des arrêts',
    loadingConfirm: 'Chargement confirmé',
    etaLabel: 'Arrivée prévue',
    tabDetails: 'Détails',
    tabHistory: 'Historique',
    tabProof: 'Preuve',
    labelOrder: 'Commande',
    labelErpRef: 'Réf. ERP',
    labelWeight: 'Poids',
    labelUnitKg: 'kg',
    labelQty: 'Qté',
    labelSource: 'Source',
    labelMin: 'min',
    labelWindow: 'Fenêtre horaire',
    labelInstructions: 'Instructions',
    labelNote: 'Note',
    sectionArticles: 'Articles',
    labelArticle: 'Article',
    labelOrdered: 'Commandé',
    labelDelivered: 'Livré',
    labelStatus: 'Statut',
    labelUnitPrice: 'Prix unit.',
    labelTotal: 'Total',
    loadingState: 'En cours...',
    createBackorder: 'Créer backorder',
    noEvents: 'Aucun événement enregistré',
    byLabel: 'par',
    reasonLabel: 'Raison:',
    noProof: 'Preuve de livraison indisponible',
    signedBL: 'BL Signé',
    photoLabel: 'Photo',
    deliveryNote: 'Bon de livraison',
    windowLabel: 'Fenêtre',
    removeStop: 'Retirer',
    removeFromRoute: 'Retirer de la tournée',
    editWindow: 'Modifier la fenêtre...',
    removeStopTitle: 'Retirer ce stop de la route?',
    reasonRequired: 'Raison',
    reasonPlaceholder: 'Expliquez pourquoi...',
    closeLabel: 'Fermer',
    cancelLabel: 'Annuler',
    startLabel: 'Début',
    endLabel: 'Fin',
    saveLabel: 'Enregistrer',
    invalidWindow: 'Fenêtre horaire invalide',
    windowOverlap: '⚠️ Ce créneau chevauche...',
  },

  // ── Routes Table Page ──────────────────────────────────────────────────
  routesTablePage: {
    // Page structure
    pageSubtitle: 'Optimisation tournées',
    pageTitle: 'Suivi des',
    pageTitleBrand: 'tournées',

    // Mobile tabs
    tabFilters: 'Filtres',
    tabList: 'Tournées',

    // New route button
    newRouteButton: 'Nouvelle Tournée',
    searchPlaceholder: 'Rechercher Client...',

    // Status filters
    filterStatusLabel: 'Filtres de Statut',
    filterAllRoutes: 'Toutes les Tournées',
    filterInProgress: 'En cours',
    filterReady: 'Prêtes au Départ',
    filterClosed: 'Historique / Clôturées',

    // Advanced filters
    advancedFiltersLabel: 'Configuration Avancée',
    filterPeriodLabel: 'Période',
    filterAllDates: 'Toutes les dates',
    filterToday: 'Aujourd\'hui',
    filterYesterday: 'Hier',
    filterWeek: '7 derniers jours',
    filterDriverLabel: 'Chauffeur',
    filterAllDrivers: 'Tous les chauffeurs',
    filterVehicleLabel: 'Véhicule',
    filterAllVehicles: 'Tous les véhicules',
    filterDepotLabel: 'Site / Dépôt',
    filterAllDepots: 'Tous les sites',
    filterZoneLabel: 'Zone Géographique',
    filterAllZones: 'Toutes les zones',
    clearFiltersButton: 'Effacer les filtres',
    refreshButton: 'Actualiser le Registre',

    // Table headers
    headerRoute: 'Route',
    headerScheduled: 'Planifié',
    headerZoneDepot: 'Zone · Dépôt',
    headerDriver: 'Chauffeur',
    headerStops: 'Arrêts',
    headerStatus: 'Statut',
    headerActions: 'Actions',

    // Detail table headers
    headerIndex: '#',
    headerClient: 'Client',
    headerAddress: 'Adresse',
    headerERP: 'Réf ERP',
    headerWeight: 'Poids',
    headerDeliveryStatus: 'Statut',

    // Route details
    noActiveStops: 'Aucun arrêt actif',
    movementLabel: 'mouvement',
    notAssigned: 'Non assigné',
    addressNotProvided: 'Adresse non renseignée',

    // Close route tooltip
    closeRouteTooltip: 'Clôturer',

    // Close route modal
    closeRouteTitle: 'Clôturer la Tournée',
    closeRouteDescription: 'Clôture définitive de "{routeName}". Toutes les livraisons non terminées seront archivées.',
    closeRouteConfirm: 'Confirmer la Clôture',
    closeRouteCancel: 'Abandonner',

    // Error messages
    loadError: 'Échec du chargement des itinéraires',
  },

  // ── Drivers Page ───────────────────────────────────────────────────────
  driversPage: {
    pageSubtitle: 'Ressources humaines',
    pageTitle: 'Gestion des',
    pageTitleBrand: 'chauffeurs',
    tabFilters: 'Filtres',
    tabList: 'Chauffeurs',
    newDriverButton: 'Nouveau chauffeur',
    importCsvButton: 'Importer CSV',
    searchPlaceholder: 'Nom ou Mobile...',
    refreshButton: 'Actualiser',
    fleetStatusLabel: 'État de la Flotte',
    totalDriversLabel: 'Effectif total',
    onMissionLabel: 'En mission',
    operationalStatusLabel: 'Statuts opérationnels',
    allFleet: 'Toute la flotte',
    busy: 'En mission',
    available: 'Disponible',
    tableHeaderDriver: 'Chauffeur',
    tableHeaderContact: 'Contact',
    tableHeaderActivity: 'Activité',
    tableHeaderActions: 'Actions',
    displayedCount: '{count} chauffeur(s) affiché(s)',
    onMissionStatus: 'En mission',
    availableStatus: 'Disponible',
    activeDelivery: 'En cours',
    onRoute: 'Sur tournée',
    free: 'Libre',
    deactivateTooltip: 'Désactiver',
    activateTooltip: 'Activer',
    editModalTitle: 'Modifier le chauffeur',
    newDriverModalTitle: 'Nouveau chauffeur',
    loadingFleet: 'Chargement de la flotte...',
    // Modal form fields
    formInstructionLabel: 'Renseignez les informations du chauffeur',
    nameLabel: 'Nom complet',
    namePlaceholder: 'Prénom Nom',
    phoneLabel: 'Numéro de téléphone',
    phonePlaceholder: '+216 XX XXX XXX',
    emailLabel: 'Adresse email',
    emailPlaceholder: 'chauffeur@entreprise.com',
    invitationEmailHelp: 'Un email d\'invitation sera envoyé à cette adresse',
    saveButton: 'Enregistrer',
    createButton: 'Créer',
    cancelButton: 'Annuler',
    // Additional modal & tooltip text
    modifyTooltip: 'Modifier',
    deleteTooltip: 'Désactiver / supprimer',
    profileModalTitle: 'Profil chauffeur',
    modifyButton: 'Modifier',
    closeButton: 'Fermer',
    activityLogTitle: 'Registre d\'activité récent',
    noActivityDetected: 'Aucune activité détectée',
    deleteDriverTitle: 'Supprimer ce chauffeur ?',
    deleteDriverDescription: 'Le profil de {driverName} sera supprimé du système. Cette action est irréversible.',
    deleteButton: 'Supprimer',
    statusActive: 'Actif',
    statusInactive: 'Inactif',
    // Account status pill labels (Enterprise standard)
    statusPending: 'En attente',
    statusSuspended: 'Suspendu',
    // Tooltips for locked actions
    pendingStatusLockedTooltip: 'Le chauffeur doit d\'abord finaliser son inscription avant toute action.',
    pendingEditLockedTooltip: 'Modification indisponible tant que le chauffeur n\'a pas activé son compte.',
    // Cancel pending invite
    cancelInviteTitle: 'Annuler l\'invitation ?',
    cancelInviteDescription: 'L\'invitation de {driverName} sera annulée et le chauffeur supprimé. Cette action est irréversible.',
    cancelInviteButton: 'Annuler l\'invitation',
    cancelInviteReasonLabel: 'Raison (optionnel)',
    cancelInviteReasonPlaceholder: 'Ex: doublon, erreur de saisie…',
    cancelInviteSuccess: 'Invitation annulée',
    // Suspend (soft) — replaces the old misleading "delete"
    suspendDriverTitle: 'Suspendre ce chauffeur ?',
    suspendDriverDescription: 'Le compte de {driverName} sera désactivé. Il ne pourra plus se connecter, mais son historique est conservé. Action réversible.',
    suspendDriverButton: 'Suspendre',
    forceLogoutButton: 'Forcer la déconnexion',
    suspendReasonLabel: 'Raison (optionnel)',
    suspendReasonPlaceholder: 'Ex: absence prolongée, enquête interne…',
    suspendSuccess: 'Chauffeur suspendu',
    // Resend invitation
    resendInviteButton: 'Renvoyer l\'invitation',
    resendInviteTooltip: 'Envoyer un nouveau code d\'activation à ce chauffeur',
    resendInviteSuccess: 'Invitation renvoyée',
    resendInProgress: 'Envoi en cours…',
    resendCooldown: 'Réessayer dans {seconds}s',
    resendRateLimited: 'Trop de tentatives. Veuillez patienter {seconds}s avant de réessayer.',
    invitationExpired: 'L\'invitation a expiré. Renvoyez un nouveau code pour continuer.',
    // Countdown
    invitationExpiresIn: 'Expire dans {time}',
    invitationExpiredChip: 'Expiré',
  },

  // ── Vehicles Page ──────────────────────────────────────────────────────
  vehiclesPage: {
    // Page header & navigation
    pageSubtitle: 'Flotte automobile',
    pageTitle: 'Gestion des',
    pageTitleBrand: 'véhicules',
    tabFilters: 'Filtres',
    tabList: 'Véhicules',
    // Vehicle types
    vehicleTypeHeavy: 'Poids Lourd',
    vehicleTypeVan: 'Fourgon',
    vehicleTypeCar: 'Commerciale',
    vehicleTypeMoto: 'Moto / Scooter',
    // Status labels
    statusEngaged: 'En Engagement',
    statusAvailable: 'Disponible',
    statusOutOfService: 'Hors-Service',
    statusRetired: 'Retiré',
    // Sidebar
    newVehicleButton: 'Ajouter un véhicule',
    searchPlaceholder: 'Recherche Technique...',
    operationalStatusLabel: 'État Opérationnel',
    fleetTotal: 'Parc Total',
    operational: 'Opérationnels',
    engaged: 'En Engagement',
    maintenance: 'Maintenance',
    logisticsCapacityLabel: 'Capacités Logistiques',
    totalTonnageLabel: 'Tonnage Global',
    fleetOccupancyLabel: 'Occupation Flotte',
    // Grid & toolbar
    displayedCount: 'Affichage de {count} unités techniques',
    noVehiclesFound: 'Aucun actif répertorié',
    // Card labels
    assignedDriver: 'Conducteur Assigné',
    unassigned: '— Non Assigné',
    capacityLabel: 'Capacité',
    volumeLabel: 'Volume',
    // Modal - Create/Edit
    modalTitle: 'Configuration Technique',
    editSubtitle: 'Éditer l\'Actif',
    createSubtitle: 'Nouvelle Ressource Stratégique',
    modalImageLabel: 'Visuel Actif',
    imageUploadButton: 'Charger',
    imageChangeButton: 'Changer',
    cancelButton: 'Annuler',
    saveButton: 'Enregistrer Certificat',
    createButton: 'Créer l\'Actif',
    // Form fields
    makeLabel: 'Marque',
    modelLabel: 'Modèle',
    plateLabel: 'Plaque',
    typeLabel: 'Type',
    capacityKgLabel: 'Capacité (KG)',
    volumeM3Label: 'Volume (M³)',
    yearLabel: 'Année',
    statusLabel: 'Statut',
    operationalStatus: 'Opérationnel',
    // Delete modal
    deleteTitle: 'Retirer le véhicule',
    deleteDescription: 'Voulez-vous vraiment retirer le véhicule {vehicleName} de la flotte ? Il pourra être réactivé ultérieurement.',
    deleteButton: 'Confirmer le Retrait',
    deleteCancel: 'Abandonner',
    // Retire/Reactivate
    retireTitle: 'Retirer le véhicule',
    retireDescription: 'Voulez-vous vraiment retirer le véhicule {vehicleName} de la flotte ? Il pourra être réactivé ultérieurement.',
    retireButton: 'Retirer',
    reactivateButton: 'Réactiver',
    editButton: 'Modifier',
  },

  // ── Depots Page ────────────────────────────────────────────────────────
  depotsPage: {
    pageSubtitle: 'Réseau logistique',
    pageTitle: 'Gestion des',
    pageTitleBrand: 'dépôts',
    depotsCount: '{count} Centres opérationnels référencés',
    newHubButton: 'Nouveau Hub',
    syncButton: 'Synchroniser ERP',
    erpReadOnlyNote: 'Les dépôts proviennent de l\'ERP (entrepôts Odoo) — synchronisez pour les mettre à jour. Lecture seule.',
    headerWarehouseCode: 'Code Entrepôt',
    coordsMissing: 'Coordonnées manquantes',
    mapInitializing: 'Initialisation Cartographie...',
    mapLoading: 'Chargement du Maillage...',
    mapTitle: 'Maillage Territorial',
    totalHubs: 'Total Hubs',
    operationalHubs: 'Opérationnels',
    registryTitle: 'Registre des Implantations',
    noDepots: 'Aucun Hub Détecté',
    headerDesignation: 'Désignation Hub',
    headerLocation: 'Localisation Physique',
    headerCoordinates: 'Coordonnées',
    headerStatus: 'Statut',
    statusOperational: 'Opérationnel',
    statusInactive: 'Inactif',
    modalTitle: 'Configuration Hub',
    modalSubtitle: 'Technical Blueprint Certification',
    cancelButton: 'Abandonner',
    saveButton: 'Enregistrer Hub',
    updateButton: 'Mettre à jour Hub',
    nameLabel: 'Nom Hub',
    nameExample: 'HUB_ALPHA_01',
    addressLabel: 'Adresse',
    addressPlaceholder: 'Localisation physique...',
    latitudeLabel: 'Latitude',
    longitudeLabel: 'Longitude',
    geometricAdjustment: 'Ajustement Géométrique',
    operationalAvailability: 'Disponibilité Opérationnelle',
    deleteTitle: 'Archivage du Hub',
    deleteDescription: 'Confirmez-vous la désactivation de {hubName} ? Le maillage territorial sera recalculé.',
    deleteButton: 'Consigner Hub',
    deleteCancel: 'Annuler',
  },

  // ── Zones Page ─────────────────────────────────────────────────────────
  zonesPage: {
    pageSubtitle: 'Réseau logistique',
    pageTitle: 'Gestion des',
    pageTitleBrand: 'zones',
    tabZones: 'Zones',
    tabMap: 'Carte',
    newZoneButton: 'Nouveau Secteur',
    syncButton: 'Synchroniser',
    syncTooltip: 'Synchroniser toutes les livraisons avec les zones actuelles',
    meshIndicators: 'Indicateurs de Maillage',
    activeSectors: 'Secteurs Actifs',
    postalPoints: 'Points Postaux',
    operationalHelp: 'Aide Opérationnelle',
    helpText: 'Définissez vos périmètres en regroupant des codes postaux. Le dispatching automatique se basera sur ces zones pour l\'optimisation.',
    registryTitle: 'Registre des Secteurs Opérationnels',
    zonesConfigured: '{count} Entités configurées',
    noZones: 'Aucune zone répertoriée',
    headerDesignation: 'Secteur / Désignation',
    headerCoverage: 'Couverture Postale',
    headerDensity: 'Densité',
    headerStatus: 'Statut',
    statusOperational: 'Opérationnel',
    statusInactive: 'Inactif',
    extraCodes: '+{count}',
    mapInitializing: 'Initialisation Maillage ASM...',
    modalTitle: 'Paramétrage du Territoire',
    modalSubtitle: 'Technical Sectorization Certification',
    cancelButton: 'Abandonner',
    saveButton: 'Enregistrer',
    sectorNameLabel: 'Nom du Secteur',
    sectorNamePlaceholder: 'Ex. Tunis Nord-Est',
    zoneColorLabel: 'Couleur de Zone',
    operationalNotesLabel: 'Notes Opérationnelles',
    operationalNotesPlaceholder: 'Détails sur la zone...',
    dispatchAvailability: 'Disponibilité Dispatch',
    postalCoverageLabel: 'Couverture Postale',
    postalCodePlaceholder: 'Code postal ex. 1000',
    conflictsDetected: 'Conflits Détectés',
    conflictWarning: 'Ces codes sont déjà assignés à d\'autres zones.',
    removeConflicts: 'Supprimer les conflits',
    activePostalPoints: 'Points postaux actifs',
    deleteTitle: 'Archivage Sectoriel',
    deleteDescription: 'Confirmez-vous la désactivation de {zoneName} ? Le maillage territorial sera recalculé.',
    deleteButton: 'Consigner Zone',
    deleteCancel: 'Annuler',
  },

  // ── Performance Page ───────────────────────────────────────────────────
  performancePage: {
    pageSubtitle: 'Performance Flux',
    pageTitle: 'Analyse',
    pageTitleBrand: 'Logistique',
    operationalDashboard: 'Tableau de Pilotage Opérationnel',
    periodLabel: 'Période :',
    periodDay: 'Auj.',
    periodWeek: 'Sem.',
    periodMonth: 'Mois',
    periodAll: 'Tout',
    lastUpdated: 'Mise à jour :',
    operationalVolume: 'Volume Opérationnel',
    completionRate: 'Taux de Complétion',
    deliveryPerformance: 'Performance des livraisons',
    avgDelay: 'Retard Moyen',
    basedOnTarget: 'Basé sur l\'heure cible',
    lifeCycle: 'Cycle de Vie',
    assignmentToDestination: 'Assignation → Destination',
    volumeCurve: 'Courbe de Volume',
    lastSevenDays: 'Activité des 7 derniers jours',
    temporalFragmentation: 'Fragmentation Temporelle',
    efficiencyByPhase: 'Efficacité par phase métier (Min)',
    driverResponse: 'Réponse Chauffeur',
    assignmentToPickup: 'Assignation → Collecte',
    depotLoading: 'Chargement Dépôt',
    pickupToTransit: 'Collecte → Départ',
    effectiveTransit: 'Transit Effectif',
    transitToCompletion: 'Transit → Destination',
    totalCycleIndex: 'Total Indice Cycle',
    densityByZone: 'Densité par Zone',
    driverPerformanceRanking: 'Classement Performance Chauffeurs',
    actor: 'Acteur',
    volume: 'Volume',
    success: 'Succès',
    delay: 'Retard',
    downloadPdfTooltip: 'Télécharger le rapport PDF',
    notAssigned: 'Non assigné',
    successSlash: 'succès',
    failureSlash: 'échecs',
    errorMissingDriverId: 'ID chauffeur manquant',
    successReportDownloaded: 'Rapport de {name} téléchargé',
    errorReportGeneration: 'Erreur lors de la génération du rapport',
  },

  // ── Audit Logs Page ───────────────────────────────────────────────────
  auditLogsPage: {
    pageSubtitle: 'Sécurité système',
    pageTitle: 'Journal',
    pageTitleBrand: 'd\'audit',
    eventsRecorded: 'Événements répertoriés',
    actionLabel: 'Action',
    actionPlaceholder: 'e.g. DELETE_ROUTE',
    actorLabel: 'Acteur',
    actorPlaceholder: 'Nom ou email',
    roleLabel: 'Entité Rôle',
    allRoles: 'Tous les rôles',
    fromLabel: 'Du',
    toLabel: 'Au',
    resetButton: 'Réinitialiser',
    timestampHeader: 'Timestamp',
    actorHeader: 'Acteur',
    roleHeader: 'Rôle',
    actionHeader: 'Nature de l\'Action',
    resourceHeader: 'Ressource Cible',
    ipHeader: 'Adresse IP',
    loadingLogs: 'Chargement des logs...',
    noLogs: 'Aucun log trouvé',
    eventId: 'ID Événement',
    engineCategory: 'Catégorie Moteur',
    payloadDetails: 'Détails du Payload',
    noTechnicalDetails: 'Aucun détail technique consigné.',
    // Timeline date grouping
    dateToday: 'Aujourd\'hui',
    dateYesterday: 'Hier',
    dateEarlierWeek: 'Plus tôt cette semaine',
    dateOlder: 'Plus ancien',
    // Timeline metadata
    byActor: 'par',
    fullId: 'Complet:',
    // Action categories
    actionFluxRoute: 'Flux Route',
    actionExecution: 'Exécution',
    actionFleetManagement: 'Gestion Parc',
    actionAssignment: 'Affectation',
    actionMeshing: 'Maillage',
    actionMobility: 'Mobilité',
    actionIntervention: 'Intervention',
    actionSystem: 'Système',
    actionErpSync: 'Sync ERP',
    actionSystemAudit: 'Audit Système',
    // Driver lifecycle actions
    actionDriverInvited: 'Chauffeur invité',
    actionDriverActivated: 'Chauffeur activé',
    actionDriverSuspended: 'Chauffeur suspendu',
    actionDriverInviteResent: 'Invitation renvoyée',
    actionDriverInviteCancelled: 'Invitation annulée',
    actionDriverUpdated: 'Chauffeur modifié',
    actionDriverPasswordReset: 'Mot de passe réinitialisé',
    actionDriverBulkImported: 'Import en masse',
    actionDriverForceLogout: 'Session chauffeur révoquée',
    actionCreateAdminUser: 'Utilisateur staff créé',
    actionToggleAdminUserStatus: 'Statut staff modifié',
    actionUpdateAdminUser: 'Profil staff mis à jour',
    actionResetAdminUserPassword: 'Réinitialisation mot de passe staff demandée',
    actionForceLogoutAdminUser: 'Session staff révoquée',
  },

  // ── Settings Page ─────────────────────────────────────────────────────
  settingsPage: {
    platformNexus: 'Plateforme ASM Track',
    pageTitle: 'Paramètres',
    pageTitleBrand: 'généraux',
    generalConfig: 'Configuration Générale',
    slaParameters: 'Paramètres Flux (SLA)',
    identitiesAccess: 'Identités & Accès',
    tabSections: 'Sections',
    tabParameters: 'Paramètres',
    systemAdmin: 'Administration Système',
    readOnlyMode: 'MODE LECTURE SEULE',
    coreService: 'Service Core',
    coreServiceDesc: 'Paramètres fondamentaux de l\'instance.',
    systemNotifications: 'Notifications Système',
    systemNotificationsDesc: 'Activer les alertes visuelles sur les retards critiques.',
    autoArchiving: 'Auto-Archivage',
    autoArchivingDesc: 'Déplacer les routes terminées vers l\'historique après 24h.',
    companyBranding: 'Informations sur l\'Entreprise',
    syncFromErp: 'Synchroniser depuis l\'ERP',
    syncFromErpHint: 'Récupère le nom, l\'adresse et l\'e-mail depuis votre ERP (Odoo)',
    instanceName: 'Nom de l\'Instance',
    supportContact: 'Contact Support',
    companyAddress: 'Adresse de l\'entreprise',
    primaryColor: 'Couleur primaire',
    slaagreement: 'Service Level Agreement (SLA)',
    slaDesc: 'Seuils temporels pour le calcul de conformité opérationnelle.',
    waitingTime: 'Délai d\'affectation (avant date planifiée)',
    waitingTimeDesc: 'Délai max avant la date planifiée (Odoo) pour affecter la commande à un chauffeur. Alerte si non affectée à temps.',
    assignmentDelay: 'Délai de Démarrage (Assign)',
    assignmentDelayDesc: 'Du démarrage prévu ou réel de la tournée jusqu\'au retrait effectif du colis',
    transitDelay: 'Délai de Départ (Pickup)',
    transitDelayDesc: 'Du retrait du colis jusqu\'au départ effectif du dépôt (début de la route)',
    accessControl: 'Contrôle d\'Accès (IAM)',
    newUser: 'Nouvel Utilisateur',
    actor: 'Acteur',
    authorization: 'Habilitation',
    creationDate: 'Date de Création',
    iamGovernance: 'Gouvernance IAM',
    cancelButton: 'Annuler',
    initializeAccess: 'Initialiser Accès',
    fullName: 'Nom Complet',
    fullNameExample: 'ex: Admin Alpha',
    loginEmail: 'Email de Connexion',
    loginEmailExample: 'admin@asmtrack.com',
    temporaryPassword: 'Mot de passe Temporaire',
    profilePrivileges: 'Privilèges du Profil',
    statusLabel: 'Statut',
    actionsLabel: 'Actions',
    modifyUserTitle: 'Modifier l\'utilisateur',
    updateAccess: 'Mettre à jour l\'accès',
    resetPassword: 'Envoyer lien de réinitialisation',
    slaThresholdCert: 'Certification de Seuil SLA',
    setpointValue: 'Valeur de Consigne (Minutes)',
    applyButton: 'Appliquer',
    // SLA Modal
    whatMeasure: 'Qu\'est-ce que cela mesure?',
    currentThreshold: 'Seuil Actuel',
    setNewThreshold: 'Définir un Nouveau Seuil',
    slaBreachWarning: 'Toute livraison dépassant ce délai sera signalée comme rupture SLA',
    recommendation: '💡 Recommandation',
    waitingRec: 'Plage typique: 60-180 minutes avant la date planifiée. La commande doit être affectée à un chauffeur avant ce seuil.',
    assignmentRec: 'Plage typique: 10-30 minutes. Du démarrage de la tournée à la récupération en entrepôt. Tenant compte de la préparation du véhicule et de la préparation des colis.',
    pickupRec: 'Plage typique: 5-15 minutes. De la récupération au départ réel. Permet le chargement et les vérifications du véhicule.',
    // Integration
    integrationConfig: 'Intégration ERP',
    erpProvider: 'Fournisseur ERP',
    erpUrl: 'URL de connexion JSON-RPC',
    erpUrlDesc: 'ex: http://odoo:8069/jsonrpc',
    erpDb: 'Nom de la base de données',
    erpUid: 'ID Utilisateur (UID)',
    erpPassword: 'Mot de passe / Clé API',
    saveConfig: 'Enregistrer la configuration',
    testConnection: 'Tester la connexion',
    testSuccess: 'Connexion ERP établie avec succès.',
    testFailed: 'Échec de la connexion ERP. Vérifiez vos identifiants.',
    noErpDesc: 'Aucun fournisseur ERP n\'est actif. Les commandes devront être saisies manuellement ou via import.',
    erpOdooDesc: 'Connexion directe à Odoo via l\'interface JSON-RPC pour la synchronisation automatique des commandes.',
    erpDuxDesc: 'Connexion à Dux (En cours d\'intégration). Prise en charge complète prévue prochainement.',
  },

  // ── Deliveries Page ────────────────────────────────────────────────────
  deliveriesPage: {
    // Page structure
    pageSubtitle: 'Réseau logistique',
    pageTitle: 'Suivi des',
    pageTitleBrand: 'livraisons',

    // Filter section
    filterLabel: 'Filtres',
    showFilters: 'Afficher les filtres',
    hideFilters: 'Masquer les filtres',
    searchPlaceholder: 'Recherche rapide',
    filterByStatus: 'Tous les statuts',
    filterByDriver: 'Tous les chauffeurs',
    filterByZone: 'Toutes les zones',
    filterClear: 'Filtres',
    dateLabel: 'Date de livraison',
    qualificationLabel: 'Qualification',
    refreshButton: 'Actualiser',
    tabList: 'Liste',

    // Quick views
    totalFlow: 'Toutes les livraisons',
    quickViewNeedsPinning: 'À épingler',
    quickViewUnassigned: 'Non assignées',
    quickViewInTransit: 'En transit',
    quickViewCompleted: 'Livrées',
    quickViewFailed: 'Échouées',
    quickViewOverdue: 'En retard',
    quickViewToday: 'Aujourd\'hui',
    quickViewFuture: 'À venir',

    // Table display
    displayLabel: 'Affichage :',
    entityDetected: 'entités flux détectées',
    pageSize: '/ page',

    // Table headers
    refHeader: 'Réf.',
    clientHeader: 'Client',
    addressHeader: 'Adresse',
    driverHeader: 'Chauffeur',
    zoneHeader: 'Zone',
    statusHeader: 'Statut',
    scheduledHeader: 'Planifié',
    actionsHeader: 'Actions',

    // Table content
    unknownDriver: 'Adresse inconnue',
    notAssigned: 'Non assigné',
    outOfZone: 'Zone non définie',
    unscheduled: 'Non planifié',

    // Pagination
    pageLabel: 'Page',
    resultsLabel: 'résultats',
    prevButton: '← Préc.',
    nextButton: 'Suiv. →',

    // Pin/Location modal
    pinModalTitle: 'Épingler la position de livraison',
    pinModalSearch: 'Rechercher une adresse...',
    pinModalConfirm: 'Position confirmée',
    lockedGeocoding: 'Position verrouillée — Livraison en cours',
    pinReverseGeocoding: 'Localisation en cours...',
    addressLocated: 'Position localisée — Cliquez sur la carte pour confirmer',
    analyzeInProgress: 'Analyse en cours...',
    addressTarget: 'Localisation Cible',
    addressPlaceholder: 'Saisie manuelle...',
    postalCodeLabel: 'Code Postal',
    postalCodePlaceholder: '20XX',
    pinButtonConfirm: 'Confirmer la localisation',
    lockedDeliveryMessage: 'Livraison active. L\'emplacement est verrouillé pour garantir l\'intégrité de la tournée optimisée.',

    // Tooltips
    tooltipPinLocation: 'Confirmer la position GPS sur la carte',
    tooltipRepin: 'Modifier la position',
    tooltipCancel: 'Annuler la livraison',
    tooltipDownloadBL: 'Télécharger le bon de livraison (PDF)',
    trackingLink: 'Copier le lien de suivi public',

    // Cancel modal
    cancelModalTitle: 'Annuler la livraison',
    cancelModalDescription: 'Cette action est irréversible. La commande sera annulée et synchronisée avec l\'ERP.',
    cancelModalLabel: 'Motif d\'annulation',
    cancelModalPlaceholder: 'Expliquez le motif de cette annulation...',
    cancelButtonConfirm: 'Annuler définitivement',
    cancelButtonKeep: 'Conserver',

    // Success/Error messages
    deliveryCancelled: 'Livraison annulée',
    deliveryCreated: 'Livraison créée',
    backorderCreated: 'Commande supplémentaire créée',
    trackingCopied: 'Lien de suivi copié',

    // Error messages
    loadError: 'Impossible de charger les livraisons',
    backorderError: 'Erreur lors de la création de la commande supplémentaire',
    pinError: 'Erreur lors de l\'enregistrement de la position',
    downloadError: 'Erreur lors du téléchargement du BL',

    // Page loading
    pageLoading: 'Chargement des livraisons...',
  },

  // ── API Messages & Errors ──────────────────────────────────────────────
  apiMessages: {
    // Success messages
    successStopCancelled: 'Arrêt retiré de la tournée',
    successStopRemoved: 'Arrêt supprimé',
    successWindowUpdated: 'Fenêtre horaire mise à jour',
    successWindowsSaved: 'Fenêtres horaires enregistrées',
    successRouteValidated: 'Tournée validée',
    successRouteReassigned: 'Tournée réaffectée',
    successRouteClosed: 'Tournée clôturée',
    successRouteCancelled: 'Tournée annulée',
    successBackorderCreated: 'Commande supplémentaire créée',
    successDeliveryRescheduled: 'Livraison remise en file de planification',
    successDeliveryReassigned: 'Livraison réaffectée',
    successPositionConfirmed: 'Position confirmée',
    successTrackingLinkCopied: 'Lien de suivi copié',
    successLogin: 'Connexion réussie',
    errorDataNotLoaded: 'Données non chargées',
    infoBlGenerating: 'Génération du BL en cours...',
    successBlDownloaded: 'BL téléchargé avec succès',
    successReportDownloaded: 'Rapport de tournée téléchargé avec succès',
    errorReportDownloadFailed: 'Impossible de télécharger le rapport de tournée',
    errorBlGenerationFailed: 'Erreur lors de la génération du BL',
    successSlaUpdated: 'Paramètres SLA mis à jour',
    successErpUpdated: 'Paramètres d\'intégration ERP mis à jour',
    successUserCreated: 'Utilisateur créé avec succès',
    successUserUpdated: 'Utilisateur mis à jour avec succès',
    successUserForceLogout: 'Utilisateur déconnecté avec succès',
    successUserPasswordResetEmail: 'E-mail de réinitialisation envoyé avec succès',
    errorUserUpdateFailed: 'Échec de la mise à jour de l\'utilisateur',
    errorUserForceLogoutFailed: 'Échec de la déconnexion de l\'utilisateur',
    errorUserPasswordResetEmailFailed: 'Échec de l\'envoi de l\'e-mail de réinitialisation',
    successDriverForceLogout: 'Chauffeur déconnecté avec succès',
    errorDriverForceLogoutFailed: 'Échec de la déconnexion du chauffeur',
    successDeliveryCreated: 'Livraison créée avec succès',
    errorDeliveryCreateFailed: 'Impossible de créer la livraison',
    errorDriverRoutesLoadFailed: 'Impossible de charger les tournées du chauffeur',
    errorNoteRequired: 'Veuillez ajouter une note explicative',
    errorTimeWindowRequired: 'Fenêtre horaire requise pour cette livraison',
    successReassignToDraft: 'Livraison(s) ajoutée(s) au brouillon de tournée',
    successReassignToActive: 'Livraison(s) réaffectée(s) avec succès',
    errorReassignPartialSuccess: "Certaines livraisons n'ont pas pu être transférées",
    errorReassignFailed: 'Impossible de réaffecter la livraison',

    // Error messages - Stop operations
    errorStopNotFound: 'Arrêt non trouvé',
    errorStopInvalidStatus: 'Arrêt dans un statut invalide',
    errorStopInTransit: 'Impossible d\'annuler un arrêt en cours de livraison',
    errorStopCancellationNotAllowed: 'Annulation d\'arrêt autorisée uniquement sur les tournées validées ou en cours',
    errorStopDoesNotBelong: 'Cet arrêt n\'appartient pas à cette tournée',

    // Error messages - Route operations
    errorRouteNotFound: 'Tournée non trouvée',
    errorRouteInvalidStatus: 'Opération non autorisée pour ce statut de tournée',
    errorRouteValidationFailed: 'Validation de la tournée échouée',
    errorRouteReassignmentFailed: 'Réaffectation de la tournée échouée',
    errorRouteClosureFailed: 'Clôture de la tournée échouée',
    errorRouteCancellationFailed: 'Annulation de la tournée échouée',
    errorOnlyValidatedRoutes: 'Seules les tournées validées ou en cours peuvent être modifiées',

    // Error messages - Delivery operations
    errorDeliveryNotFound: 'Livraison non trouvée',
    errorDeliveryInvalidStatus: 'Opération non autorisée pour ce statut de livraison',
    errorBackorderCreationFailed: 'Création de commande supplémentaire échouée',
    errorDeliveryReassignmentFailed: 'Réaffectation de livraison échouée',

    // Error messages - Time window
    errorWindowInvalid: 'Fenêtre horaire invalide',
    errorWindowOutOfBounds: 'Fenêtre horaire en dehors des bornes de la tournée',
    errorWindowOverlap: 'Fenêtre horaire en conflit avec un autre arrêt',
    errorWindowUpdateFailed: 'Mise à jour de la fenêtre horaire échouée',

    // Error messages - Validation
    errorMissingGPS: 'Certains arrêts n\'ont pas de coordonnées GPS',
    errorMissingLocation: 'Localisation manquante',
    errorCapacityExceeded: 'Capacité véhicule dépassée',
    errorVehicleAlreadyAssigned: 'Le véhicule sélectionné est déjà assigné',
    errorInvalidOrderSequence: 'Séquence d\'arrêts invalide',

    // Error messages - API/Network
    errorBadRequest: 'Demande invalide',
    errorUnauthorized: 'Vous n\'êtes pas autorisé à effectuer cette action',
    errorForbidden: 'Accès refusé',
    errorConflict: 'Conflit de données - l\'objet a peut-être été modifié',
    errorServerError: 'Erreur serveur - veuillez réessayer',
    errorNetworkError: 'Erreur réseau - vérifiez votre connexion',
    errorTimeoutError: 'La requête a dépassé le délai d\'attente',
    errorUnknownError: 'Une erreur est survenue',

    // Generic/Common
    errorDataLoadFailed: 'Impossible de charger les données',
    errorSaveFailed: 'Impossible de sauvegarder les modifications',
    errorDeleteFailed: 'Impossible de supprimer l\'élément',
    errorExportFailed: 'Erreur lors de l\'exportation',
    errorImportFailed: 'Erreur lors de l\'importation',

    // Company CRUD
    successCompanyCreated: 'Entreprise créée avec succès',
    successCompanyUpdated: 'Entreprise mise à jour avec succès',
    successCompanySynced: 'Informations synchronisées depuis l\'ERP',
    errorCompanySyncFailed: 'Échec de la synchronisation depuis l\'ERP',
    successCompanyDeactivated: 'Entreprise désactivée avec succès',
    errorCompanyCreateFailed: 'Impossible de créer l\'entreprise',
    errorCompanyUpdateFailed: 'Impossible de mettre à jour l\'entreprise',
    errorCompanyDeactivateFailed: 'Impossible de désactiver l\'entreprise',
    errorCompaniesLoadFailed: 'Impossible de charger les entreprises',
    errorCompanyNameRequired: 'Nom d\'entreprise requis',

    // Depot CRUD
    successDepotCreated: 'Dépôt créé avec succès',
    successDepotUpdated: 'Dépôt mis à jour avec succès',
    successDepotDeleted: 'Dépôt supprimé avec succès',
    errorDepotCreateFailed: 'Impossible de créer le dépôt',
    errorDepotUpdateFailed: 'Impossible de mettre à jour le dépôt',
    errorDepotDeleteFailed: 'Impossible de supprimer le dépôt',
    errorDepotsLoadFailed: 'Impossible de charger les dépôts',
    successDepotGeolocate: 'Position du dépôt localisée avec succès',
    errorDepotGeolocateFailed: 'Emplacement du dépôt introuvable',
    successDepotSync: 'Adresse du dépôt synchronisée avec succès',
    successDepotSynced: 'Dépôts synchronisés depuis l\'ERP',
    errorDepotSyncFailed: 'Erreur de synchronisation de l\'adresse du dépôt',
    errorDepotAddressRequired: 'Adresse du dépôt requise',
    errorDepotNameRequired: 'Nom du dépôt requis',

    // Driver CRUD
    successDriverCreated: 'Chauffeur invité avec succès',
    successDriverUpdated: 'Chauffeur mis à jour avec succès',
    successDriverActivated: 'Chauffeur activé avec succès',
    successDriverDeactivated: 'Chauffeur désactivé avec succès',
    successDriverSuspended: 'Chauffeur suspendu avec succès',
    successDriverInviteCancelled: 'Invitation annulée',
    successDriverInviteResent: 'Invitation renvoyée',
    errorDriverCreateFailed: 'Impossible d\'inviter le chauffeur',
    errorDriverUpdateFailed: 'Impossible de mettre à jour le chauffeur',
    errorDriverDeactivateFailed: 'Impossible de désactiver le chauffeur',
    errorDriverSuspendFailed: 'Impossible de suspendre le chauffeur',
    errorDriverCancelInviteFailed: 'Impossible d\'annuler l\'invitation',
    errorDriverInviteResendFailed: 'Impossible de renvoyer l\'invitation',
    errorDriversLoadFailed: 'Impossible de charger les chauffeurs',
    errorDriverNameRequired: 'Nom et téléphone du chauffeur requis',
    errorDriverEmailRequired: 'Email du chauffeur requis',
    errorDriverInviteExpired: 'L\'invitation a expiré. Renvoyez un nouveau code.',
    errorPendingStatusLocked: 'Impossible de modifier le statut d\'un compte en attente d\'activation.',
    errorDriverRateLimited: 'Trop de tentatives. Veuillez patienter.',
    successDriversImported: 'Chauffeurs importés avec succès',

    // Import
    successImportCompleted: 'Importation des commandes terminée',
    errorImportBatchFailed: 'Certaines commandes n\'ont pas pu être importées',
    successImportSingle: 'Commande importée avec succès',
    errorImportSingleFailed: 'Impossible d\'importer la commande',
    errorImportAlreadyExists: 'Cette commande existe déjà dans le système',

    // Vehicle CRUD
    successVehicleCreated: 'Véhicule créé avec succès',
    successVehicleUpdated: 'Véhicule mis à jour avec succès',
    successVehicleDeleted: 'Véhicule retiré avec succès',
    successVehicleReactivated: 'Véhicule réactivé avec succès',
    errorVehicleCreateFailed: 'Impossible de créer le véhicule',
    errorVehicleUpdateFailed: 'Impossible de mettre à jour le véhicule',
    errorVehicleDeleteFailed: 'Impossible de retirer le véhicule',
    errorVehicleReactivateFailed: 'Impossible de réactiver le véhicule',
    errorVehiclesLoadFailed: 'Impossible de charger les véhicules',
    errorVehiclePlateRequired: 'Matricule du véhicule requis',

    // Zone CRUD
    successZoneCreated: 'Zone créée avec succès',
    successZoneUpdated: 'Zone mise à jour avec succès',
    successZoneDeleted: 'Zone supprimée avec succès',
    errorZoneCreateFailed: 'Impossible de créer la zone',
    errorZoneUpdateFailed: 'Impossible de mettre à jour la zone',
    errorZoneDeleteFailed: 'Impossible de supprimer la zone',
    errorZonesLoadFailed: 'Impossible de charger les zones',
    successZonesSynced: 'Livraisons réaffectées aux zones',
    errorZonesSyncFailed: 'Impossible de synchroniser les zones',
    errorZoneNameRequired: 'Nom de la zone requis',
    errorZoneMinPostalCodesRequired: 'Au moins un code postal requis',
    errorZoneConflictingCodes: 'Certains codes postaux sont déjà assignés',
    errorZoneCodeAlreadyAdded: 'Code postal déjà ajouté',
    errorZoneCodeLookupFailed: 'Code postal introuvable',

    // Undo / Optimistic
    successUndoAction: 'Action annulée avec succès',
    errorUndoActionFailed: 'Impossible d\'annuler l\'action',
    errorActionFailed: 'L\'action a échoué',
  },

  // ── sidebar ─────────────────────────────────────────────────────────────
  sidebar: {
    collapse: 'Réduire',
    groups: {
      operations: 'Opérations',
      deliveries: 'Livraisons',
      planning: 'Planification',
      fleet: 'Flotte',
      analytics: 'Analyses',
      platform: 'Plateforme',
    },
    items: {
      dashboard: 'Tableau de bord',
      overview: "Vue d'ensemble",
      dispatch: 'Centre de dispatch',
      tracking: 'Suivi',
      import: 'Importation',
      createRoute: 'Créer tournée',
      routes: 'Tournées',
      drivers: 'Chauffeurs',
      vehicles: 'Véhicules',
      depots: 'Dépôts',
      zones: 'Zones',
      performance: 'Analyse de performance',
      audit: 'Audit',
      settings: 'Paramètres',
      erpIntegration: 'Intégration ERP',
      companies: 'Entreprises',
    }
  },

  // ── notifications ────────────────────────────────────────────────────────
  notifications: {
    FAILED: {
      title: 'Échec de livraison',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — livraison échouée${p.motif ? ` · ${p.motif}` : ''}`,
    },
    DELIVERED: {
      title: 'Livraison réussie',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — livrée`,
    },
    'delivery.created': {
      title: 'Nouvelle livraison',
      message: (p: any) => {
        const base = p.orderId
          ? `Commande ${p.orderId} créée${p.clientName ? ` · ${p.clientName}` : ''}`
          : `Nouvelle commande${p.clientName ? ` · ${p.clientName}` : ''}`;
        return base;
      },
    },
    'delivery.scheduled': {
      title: 'Livraison planifiée',
      message: (p: any) => {
        const parts = [`${p.clientName || 'Client'} — planifiée`];
        if (p.driverName) parts.push(p.driverName);
        if (p.dropoffAddress) parts.push(p.dropoffAddress);
        return `${refTag(p)}${parts.join(' · ')}`;
      },
    },
    'delivery.picked_up': {
      title: 'Colis récupéré',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — pris en charge`,
    },
    'delivery.in_transit': {
      title: 'En livraison',
      message: (p: any) => {
        const eta = fmtEtaFr(p.etaAt);
        const parts = [`${p.clientName || 'Client'} — en route`];
        if (p.driverName) parts.push(p.driverName);
        if (eta) parts.push(`ETA ${eta}`);
        if (p.routeDistanceKm) parts.push(`${Number(p.routeDistanceKm).toFixed(1)} km`);
        return `${refTag(p)}${parts.join(' · ')}`;
      },
    },
    'delivery.completed': {
      title: 'Livraison réussie',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — livrée`,
    },
    'delivery.failed': {
      title: 'Échec livraison',
      message: (p: any) => {
        const parts = [`${p.clientName || 'Client'} — échouée`];
        if (p.motif) parts.push(p.motif);
        if (p.driverName) parts.push(p.driverName);
        return `${refTag(p)}${parts.join(' · ')}`;
      },
    },
    'delivery.cancelled': {
      title: 'Livraison annulée',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — annulée`,
    },
    'delivery.reassigned': {
      title: 'Livraison réassignée',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — nouveau livreur${p.driverName ? ` · ${p.driverName}` : ''}`,
    },
    'delivery.reassigned_away': {
      title: 'Livraison retirée',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — retirée de la tournée du livreur`,
    },
    'delivery.handoff_required': {
      title: 'Passation requise',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — passation de colis requise${p.driverName ? ` · ${p.driverName}` : ''}`,
    },
    'delivery.replanned': {
      title: 'Livraison replanifiée',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — reportée`,
    },
    'route.validated': {
      title: 'Tournée validée',
      message: (p: any) => {
        const n = Number(p.stopCount);
        const win = fmtWindow(p.plannedStartTime, p.plannedEndTime);
        const parts = [`«${p.routeName || 'Tournée'}» — prête à démarrer`];
        if (Number.isFinite(n) && n > 0) parts.push(stopsFr(n));
        if (win) parts.push(win);
        if (p.driverName) parts.push(p.driverName);
        return parts.join(' · ');
      },
    },
    'ROUTE_STARTED': {
      title: 'Tournée démarrée',
      message: (p: any) => {
        const n = Number(p.stopCount);
        const parts = [`${p.driverName || 'Le livreur'} a démarré «${p.routeName || 'la tournée'}»`];
        if (Number.isFinite(n) && n > 0) parts.push(stopsFr(n));
        return parts.join(' · ');
      },
    },
    'PICKUP_CONFIRMED': {
      title: 'Chargement confirmé',
      message: (p: any) => {
        const n = Number(p.parcelCount);
        const colis = Number.isFinite(n) && n > 0 ? `${n} colis` : 'colis';
        const depot = p.depotName ? ` au dépôt ${p.depotName}` : '';
        return `${p.driverName || 'Le livreur'} a chargé ${colis}${depot} · «${p.routeName || 'tournée'}»`;
      },
    },
    'route.schedule_changed': {
      title: 'Planning modifié',
      message: (p: any) => {
        const win = fmtWindow(p.plannedStartTime, p.plannedEndTime);
        return win
          ? `«${p.routeName || 'Tournée'}» — nouveau créneau ${win}`
          : `«${p.routeName || 'Tournée'}» — horaire mis à jour`;
      },
    },
    'route.stop_added': {
      title: 'Arrêt ajouté',
      message: (p: any) => p.clientName ? `${p.clientName} ajouté à «${p.routeName || 'la tournée'}»` : `«${p.routeName || 'Tournée'}» — nouvel arrêt`,
    },
    'route.stop_removed': {
      title: 'Arrêt retiré',
      message: (p: any) => p.clientName ? `${p.clientName}${p.erpOrderId ? ` [${p.erpOrderId}]` : ''} retiré de «${p.routeName || 'la tournée'}»${p.reason ? ` — ${p.reason}` : ''}` : `«${p.routeName || 'Tournée'}» — arrêt supprimé`,
    },
    'delivery.handoff_confirmed': {
      title: 'Transfert colis confirmé',
      message: (p: any) => `Colis${p.clientName ? ` de ${p.clientName}` : ''} remis au nouveau livreur`,
    },
    'handoff.requested': {
      title: 'Passation requise',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — passation ${p.fromDriverName || '—'} → ${p.toDriverName || '—'}`,
    },
    'handoff.overdue': {
      title: 'Passation en retard',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — passation non confirmée (${p.fromDriverName || '—'} → ${p.toDriverName || '—'})`,
    },
    'handoff.cancelled': {
      title: 'Passation annulée',
      message: (p: any) => `${refTag(p)}${p.clientName || 'Client'} — passation annulée${p.reason ? ` · ${p.reason}` : ''}`,
    },
    'sla.breach': {
      title: 'Alerte SLA',
      message: (p: any) => {
        const head = `${refTag(p)}${p.clientName || 'Client'} — `;
        if (p.motif === 'SLA_WAITING') {
          return `${head}En attente d'affectation depuis ${p.elapsed} min (limite ${p.limit} min)`;
        }
        if (p.motif === 'SLA_ASSIGNMENT') {
          return `${head}Démarrage en retard : ${p.elapsed} min depuis l'affectation (limite ${p.limit} min)`;
        }
        if (p.motif === 'SLA_PICKUP') {
          return `${head}Départ du dépôt en retard : ${p.elapsed} min (limite ${p.limit} min)`;
        }
        if (p.motif === 'SLA_TRANSIT') {
          return `${head}Livraison en retard sur le créneau prévu`;
        }
        return p.slaMessage || `${head}Retard critique (SLA)`;
      },
    },
    'STOPS_TRANSFERRED_OUT': {
      title: 'Arrêts transférés',
      message: (p: any) => `Des arrêts ont été retirés de «${p.routeName || 'la tournée'}»`,
    },
    'STOPS_TRANSFERRED_IN': {
      title: 'Arrêts reçus',
      message: (p: any) => `Des arrêts ont été ajoutés à «${p.routeName || 'la tournée'}»`,
    },
    'erp.sync_failed': {
      title: 'Échec de synchronisation ERP',
      message: (p: any) => {
        const op = ({ STOCK: 'mise à jour de stock', CANCELLATION: 'annulation', FAILURE_REPORT: "rapport d'échec" } as Record<string, string>)[p.motif] || '';
        return `${refTag(p)}${p.clientName || 'Commande'} — échec de synchronisation ERP${op ? ` (${op})` : ''}. Intervention requise.`;
      },
    },
    'erp.orders_ready': {
      title: 'Commandes ERP prêtes',
      message: (p: any) => `${p.count || 'Nouvelles'} commande${Number(p.count) > 1 ? 's' : ''} ERP en attente d'import — à valider`,
    },
  },
  landingPage: {
    metaTitle: "ASM Track — Orchestration Logistique",
    heroBadge: "Plateforme d'orchestration logistique",
    heroTitle1: "La logistique",
    heroTitle2: "réinventée.",
    heroDesc: "Orchestration temps réel, dispatch intelligent et suivi de flotte — en un seul système nerveux.",
    heroCta: "Contacter l'équipe",
    heroDashboard: "Accéder au dashboard →",
    statDeliveries: "Livraisons",
    statOptimization: "Optimisation",
    statUptime: "Disponibilité",
    featuresTitle: "Fonctionnalités",
    featuresSubtitle1: "Chaque livraison,",
    featuresSubtitle2: "sous contrôle.",
    featureRealtime: "Tracking temps réel",
    featureRealtimeDesc: "Visualisez l'emplacement exact de chaque chauffeur et l'avancement de chaque tournée en direct.",
    featureDispatch: "Dispatch & réaffectation",
    featureDispatchDesc: "Réassignez des livraisons en vol avec alertes automatiques. Zéro friction, décision en 3 clics.",
    featureErp: "Intégration ERP/Odoo",
    featureErpDesc: "Synchronisation bidirectionnelle native avec Odoo — commandes, stocks et bons de livraison.",
    manifestoTitle: "Manifeste",
    manifestoDesc: "La plupart des outils logistiques se concentrent sur la planification.",
    manifestoFocus1: "Nous nous concentrons sur",
    manifestoFocus2: "l'exécution.",
    manifestoPillar1: "Réactivité",
    manifestoPillar1Desc: "Chaque incident est détecté et adressé avant que le client ne le remarque.",
    manifestoPillar2: "Transparence",
    manifestoPillar2Desc: "Chaque acteur voit exactement ce qu'il a besoin de voir, ni plus ni moins.",
    manifestoPillar3: "Fiabilité",
    manifestoPillar3Desc: "Les promesses de livraison sont tenues. Systématiquement. Sans exception.",
    methodTitle: "Méthode",
    methodSubtitle1: "De la commande",
    methodSubtitle2: "à la signature.",
    methodStep1: "Import & Planification",
    methodStep1Desc: "Les commandes ERP sont importées automatiquement. L'IA génère les tournées optimales en moins de 2 secondes.",
    methodStep2: "Dispatch & Suivi en vol",
    methodStep2Desc: "Les chauffeurs reçoivent leurs tournées en temps réel. Chaque mouvement est tracé et visible depuis le tableau de bord.",
    methodStep3: "Analyse & Optimisation",
    methodStep3Desc: "Chaque tournée terminée alimente les algorithmes d'optimisation pour améliorer continuellement les performances.",
    ctaTitle: "Commençons",
    ctaSubtitle1: "Prêt à prendre",
    ctaSubtitle2: "le contrôle.",
    ctaDesc: "Contactez l'équipe ASM Track pour une démonstration personnalisée de la plateforme.",
    ctaButton: "Contacter l'équipe →",
    footerDesc: "Système nerveux logistique de nouvelle génération.",
    footerNav: "Navigation",
    footerContact: "Contact",
    footerRights: "© 2026 ASMTRACK — LOGICIEL INDUSTRIEL",
    navFeatures: "Fonctionnalités",
    navMethod: "Méthode",
    navContact: "Contact",
    navLogin: "Connexion",
  },
  loginPage: {
    auth: "Authentification",
    welcomeBack: "Bon retour",
    signInToContinue: "Connectez-vous à votre compte pour continuer.",
    title1: "Accéder au",
    title2: "dashboard.",
    emailLabel: "Adresse email",
    emailPh: "votre@email.com",
    passLabel: "Mot de passe",
    passPh: "••••••••••",
    button: "Accéder au Dashboard",
    buttonLoading: "Connexion...",
    needAccess: "Besoin d'accès ?",
    contactTeam: "Contacter l'équipe →",
    back: "Retour",
    errorDefault: "Email ou mot de passe invalide",
    successToast: "Connexion réussie",
    brandTagline: "Planifier. Suivre. Livrer.",
    pillLogistics: "Orchestration logistique",
    pillRealtime: "Orchestration temps réel et fiabilité opérationnelle totale.",
    pillPilotez: "Pilotez avec",
    pillCertitude: "Certitude.",
  },
  mapSection: {
    dispatch: {
      title: "Planification\nautomatique",
      desc: "Commandes ERP importées. Tournées optimisées en 2 secondes.",
      tag: "Dispatch",
    },
    scooter: {
      title: "Chauffeur\nnotifié",
      desc: "Tournée envoyée en temps réel. Navigation intégrée.",
      tag: "En route",
    },
    delivery: {
      title: "Livraison\nconfirmée",
      desc: "Signature électronique. Photo preuve de dépôt.",
      tag: "Livraison",
    },
    confirm: {
      title: "Mission\naccomplie",
      desc: "Rapport automatique généré. KPIs mis à jour.",
      tag: "Terminé",
    },
  },

  // ── Public Tracking Page ───────────────────────────────────────────────────
  trackingPage: {
    statusEnAttente: 'En attente',
    statusSubEnAttente: 'Votre commande est en file d\'attente',
    statusPlanifiee: 'Planifiée',
    statusSubPlanifiee: 'Votre livraison a été planifiée',
    statusPriseEnCharge: 'Prise en charge',
    statusSubPriseEnCharge: 'Le livreur a récupéré votre colis',
    statusEnRoute: 'En route',
    statusSubEnRoute: 'Votre livreur est en chemin',
    statusLivree: 'Livrée',
    statusSubLivree: 'Votre commande a été livrée avec succès',
    statusTentativeEchouee: 'Tentative échouée',
    statusSubTentativeEchouee: 'Une nouvelle tentative sera planifiée',
    statusAnnulee: 'Annulée',
    statusSubAnnulee: 'Cette livraison a été annulée',
    stepPlanifiee: 'Planifiée',
    stepRecuperee: 'Récupérée',
    stepEnRoute: 'En route',
    stepLivree: 'Livrée',
    notFoundTitle: 'Livraison introuvable',
    notFoundSub: 'Vérifiez votre code de suivi ou contactez l\'expéditeur.',
    appTitle: 'ASM Track',
    pageTitle: 'Suivi de commande',
    labelDriver: 'Livreur',
    labelCall: 'Appeler',
    sectionContents: 'Contenu de la commande',
    labelCod: 'Paiement à la livraison',
    currencyTnd: 'TND',
    labelTotal: 'Total',
    noItems: 'Détails des articles non disponibles.',
    sectionAddress: 'Adresse de livraison',
    sectionSlot: 'Créneau de livraison',
    liveTracking: 'Suivi en temps réel · ',
  },
} as const;

// ── Recursive Locale Proxy (SaaS Grade) ─────────────────────────────────

const createLocaleProxy = (frObj: any, enObj: any, arObj: any): any => {
  return new Proxy(frObj, {
    get(target, prop) {
      let locale = 'fr';

      if (typeof window !== 'undefined') {
        // Client: try to get from store, fall back to cookie
        locale = useLocaleStore.getState().locale || getLocaleFromCookie() || 'fr';
      } else {
        // Server: try to get from cookies if available
        locale = getLocaleFromCookie() || 'fr';
      }

      const activeObj = locale === 'ar'
        ? arObj
        : (locale === 'en' ? enObj : frObj);

      const val = activeObj[prop];
      if (val && typeof val === 'object' && !Array.isArray(val)) {
        return createLocaleProxy(frObj[prop], enObj[prop], arObj[prop]);
      }
      return val;
    }
  });
};

export const UX_COPY = createLocaleProxy(FR_COPY, EN_COPY, AR_COPY) as typeof FR_COPY;
export type UxCopyType = typeof FR_COPY;
