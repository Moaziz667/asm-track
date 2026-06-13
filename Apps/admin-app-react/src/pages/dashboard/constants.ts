import type { DeliveryStatus } from '@/types';

// Kanban card status colors.
export const STATUS_COLOR_MAP: Record<DeliveryStatus, string> = {
  UNSCHEDULED:          '#C4881A',
  SCHEDULED:            '#5E6AD2',
  PICKED_UP:            '#2594B8',
  IN_TRANSIT:           '#D4772C',
  DELIVERED:            '#4CAF82',
  PARTIALLY_DELIVERED:  '#7B6FCC',
  FAILED:               '#C7372F',
  CANCELLED:            '#8A8F98',
};

export const KANBAN_GRADIENT_MAP: Record<string, string> = {
  UNSCHEDULED:          'var(--gradient-orange)',
  SCHEDULED:            'var(--gradient-purple)',
  PICKED_UP:            'var(--gradient-teal)',
  IN_TRANSIT:           'var(--gradient-blue)',
  FAILED:               'var(--gradient-fuchsia)',
  DELIVERED:            'var(--gradient-blue)',
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
