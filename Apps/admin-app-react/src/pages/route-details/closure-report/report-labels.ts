/**
 * French strings for the Rapport de Tournée that aren't inlined in the component.
 * Kept minimal — the report renders most of its copy inline; only the shared movement
 * labels and the error title live here.
 */

export const REPORT_LABELS = {
  errorTitle: 'Impossible de charger le rapport',

  movement: {
    REPLANNED:              'Replanifié',
    CANCELLED:              'Annulé',
    HANDOFF:                'Transfert',
    STOP_REMOVED_REPLANNED: 'Arrêt retiré · Replanifié',
    STOP_REMOVED_CANCELLED: 'Arrêt retiré · Annulé',
    HANDOFF_CONFIRMED:      'Transfert confirmé',
    STOP_FAILED:            'Échec d’arrêt',
    DELIVERY_CANCELLED:     'Livraison annulée',
  } as Record<string, string>,
} as const;
