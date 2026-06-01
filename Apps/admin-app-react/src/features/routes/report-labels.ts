/**
 * All French strings used in the Rapport de Tournée.
 * Single source of truth — keeps the UI consistent and the labels reviewable.
 */

import type { StopClassification } from './report-types';

export const REPORT_LABELS = {
  title:        'Rapport de Tournée',
  subtitle:     'Synthèse de clôture',
  generatedAt:  'Généré le',
  downloadPdf:  'Télécharger PDF',
  exportExcel:  'Exporter Excel',
  loading:      'Chargement du rapport…',
  errorTitle:   'Impossible de charger le rapport',
  empty:        'Aucune donnée disponible pour cette tournée.',

  sections: {
    header:      'Informations',
    kpis:        'Indicateurs clés',
    breakdown:   'Répartition des arrêts',
    timeline:    'Chronologie des arrivées',
    map:         'Trajet final',
    stops:       'Détail des arrêts',
    movements:   'Mouvements et exceptions',
    pods:        'Preuves de livraison',
    audit:       'Journal des transitions',
  },

  kpis: {
    completionRate:    'Taux de complétion',
    completionFootnote:'arrêts livrés sur arrêts tentés',
    onTimeRate:        'Taux de ponctualité',
    onTimeFootnote:    'parmi les arrêts livrés',
    totalDistance:     'Distance totale',
    activeDuration:    'Durée active',
    cumulativeDelay:   'Retard cumulé',
    failedStops:       'Échecs',
    removedStops:      'Retirés',
    startDelay:        'Retard démarrage',
    attempted:         'Arrêts tentés',
    planned:           'planifiés',
  },

  header: {
    route:       'Tournée',
    date:        'Date',
    driver:      'Chauffeur',
    vehicle:     'Véhicule',
    plate:       'Plaque',
    depot:       'Dépôt',
    startedAt:   'Démarrée à',
    closedAt:    'Clôturée à',
    plannedHours:'Horaires planifiés',
    duration:    'Durée totale',
  },

  classification: {
    ON_TIME:        'À l’heure',
    LATE:           'En retard',
    EARLY:          'En avance',
    PARTIAL:        'Partielle',
    FAILED:         'Échec',
    FAILED_ATTEMPT: 'Tentative échouée',
    REPLANNED:      'Replanifié',
    CANCELLED:      'Annulé',
    PENDING:        'En attente',
  } as Record<StopClassification, string>,

  movement: {
    REPLANNED:      'Replanifié',
    CANCELLED:      'Annulé',
    HANDOFF:        'Transfert',
    STOP_REMOVED_REPLANNED: 'Arrêt retiré · Replanifié',
    STOP_REMOVED_CANCELLED: 'Arrêt retiré · Annulé',
    HANDOFF_CONFIRMED:       'Transfert confirmé',
    STOP_FAILED:             'Échec d’arrêt',
    DELIVERY_CANCELLED:      'Livraison annulée',
  } as Record<string, string>,

  table: {
    stopOrder:    '#',
    client:       'Client',
    address:      'Adresse',
    window:       'Créneau',
    arrived:      'Arrivée',
    completed:    'Livré à',
    delay:        'Retard',
    status:       'Statut',
    movement:     'Mouvement',
    actions:      'Actions',
    pod:          'POD',
    yes:          'Oui',
    no:           'Non',
  },

  breakdown: {
    completed:    'Livrés',
    partial:      'Partiels',
    failed:       'Échoués',
    replanned:    'Replanifiés',
    cancelled:    'Annulés',
  },
} as const;

/**
 * Color for each classification — used by the donut, timeline, and pills.
 * Aligned with `lib/design-tokens.ts` status colors.
 */
export const CLASSIFICATION_COLORS: Record<StopClassification, { bg: string; fg: string; dot: string }> = {
  ON_TIME:        { bg: '#F0FDF4', fg: '#15803D', dot: '#16A34A' },
  LATE:           { bg: '#FEF2F2', fg: '#B91C1C', dot: '#DC2626' },
  EARLY:          { bg: '#EFF6FF', fg: '#1D4ED8', dot: '#3B82F6' },
  PARTIAL:        { bg: '#FFFBEB', fg: '#B45309', dot: '#F59E0B' },
  FAILED:         { bg: '#FEF2F2', fg: '#991B1B', dot: '#B91C1C' },
  FAILED_ATTEMPT: { bg: '#FEF2F2', fg: '#C2410C', dot: '#EA580C' },
  REPLANNED:      { bg: '#EEF2FF', fg: '#4338CA', dot: '#6366F1' },
  CANCELLED:      { bg: '#F3F4F6', fg: '#4B5563', dot: '#9CA3AF' },
  PENDING:        { bg: '#F9FAFB', fg: '#6B7280', dot: '#9CA3AF' },
};

export const BREAKDOWN_COLORS: Record<string, string> = {
  COMPLETED:  '#16A34A',
  PARTIAL:    '#F59E0B',
  FAILED_ALL: '#DC2626',
  REPLANNED:  '#6366F1',
  CANCELLED:  '#9CA3AF',
};
