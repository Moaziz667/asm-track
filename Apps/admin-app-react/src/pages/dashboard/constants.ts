import type { DeliveryStatus } from '@/types';

export type StatusTone = 'warning' | 'info' | 'brand' | 'success' | 'danger' | 'muted';

// Semantic tone map for the dispatch-flow kanban. Replaces the old per-status hex palette
// so columns/cards follow the design-system token ramp and stay anti-slop compliant.
export const STATUS_TONE_MAP: Record<DeliveryStatus, StatusTone> = {
  UNSCHEDULED:         'warning',
  SCHEDULED:           'info',
  PICKED_UP:           'info',
  IN_TRANSIT:          'brand',
  DELIVERED:           'success',
  PARTIALLY_DELIVERED: 'success',
  FAILED:              'danger',
  CANCELLED:           'muted',
};

// CSS variable for each tone — used for column header accents and card metadata.
export const TONE_VAR: Record<StatusTone, string> = {
  warning: 'var(--warning)',
  info:    'var(--info)',
  brand:   'var(--brand)',
  success: 'var(--success)',
  danger:  'var(--danger)',
  muted:   'var(--text-muted)',
};

export const DISPATCH_STATUSES: DeliveryStatus[] = [
  'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'FAILED', 'DELIVERED',
];

// Realtime events that can move a dashboard KPI. Excludes high-frequency noise
// (driver.location_updated) so a moving truck doesn't trigger constant refetches.
export const DASHBOARD_EVENTS = [
  'delivery.created', 'delivery.scheduled', 'delivery.completed', 'delivery.failed',
  'delivery.cancelled', 'delivery.in_transit', 'delivery.reassigned', 'sla.breach',
  'erp.orders_ready', 'route.validated',
] as const;
