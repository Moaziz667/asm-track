import { format } from 'date-fns';

// Effective scheduled date for a delivery: rescheduled ∨ scheduled ∨ created.
export interface CalDelivery {
  deliveryId: string;
  orderRef?: string;
  clientName?: string;
  dropoffCity?: string;
  status: string;
  routeId?: string;
  routeName?: string;
  driverId?: string;
  driverName?: string;
  scheduledAt?: string;
  rescheduledAt?: string;
  createdAt?: string;
  routeEtaAt?: string;
  totalAmount?: number;
  totalWeightKg?: number;
  itemsSummary?: string;
  failureCode?: string;
  failReason?: string;
  zoneName?: string;
  currency?: string;
}

export type StatusTone = 'warning' | 'info' | 'brand' | 'success' | 'danger' | 'muted';

export const STATUS_TONE_MAP: Record<string, StatusTone> = {
  UNSCHEDULED:         'warning',
  SCHEDULED:           'info',
  PICKED_UP:           'info',
  IN_TRANSIT:          'brand',
  DELIVERED:           'success',
  PARTIALLY_DELIVERED: 'success',
  FAILED:              'danger',
  CANCELLED:           'muted',
};

export const TONE_VAR: Record<StatusTone, string> = {
  warning: 'var(--warning)',
  info:    'var(--info)',
  brand:   'var(--brand)',
  success: 'var(--success)',
  danger:  'var(--danger)',
  muted:   'var(--text-muted)',
};

// Terminal (outcome) statuses — used to decide whether a day is "done".
export const TERMINAL = new Set(['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED']);

export const isoDay = (d: Date) => format(d, 'yyyy-MM-dd');
export const effectiveDate = (d: CalDelivery) => d.rescheduledAt || d.scheduledAt || d.createdAt;

export const startOfToday = () => {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d;
};
