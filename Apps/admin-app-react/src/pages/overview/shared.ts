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

export type OverviewView = 'month' | 'day' | 'timeline';

export const STATUS_COLOR: Record<string, string> = {
  UNSCHEDULED: '#C4881A', SCHEDULED: '#5E6AD2', PICKED_UP: '#2594B8', IN_TRANSIT: '#D4772C',
  DELIVERED: '#4CAF82', PARTIALLY_DELIVERED: '#7B6FCC', FAILED: '#C7372F', CANCELLED: '#8A8F98',
};

// Terminal (outcome) statuses — used to decide whether a day is "done".
export const TERMINAL = new Set(['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED']);

export const isoDay = (d: Date) => format(d, 'yyyy-MM-dd');
export const effectiveDate = (d: CalDelivery) => d.rescheduledAt || d.scheduledAt || d.createdAt;

/** Best "when" signal for intraday placement: route ETA ∨ a non-midnight scheduled time. */
export function timeOfDay(d: CalDelivery): { hour: number; minute: number } | null {
  const src = d.routeEtaAt || d.scheduledAt;
  if (!src) return null;
  const dt = new Date(src);
  const h = dt.getHours();
  const m = dt.getMinutes();
  // Treat exact midnight as "no precise time" (date-only schedule).
  if (h === 0 && m === 0 && !d.routeEtaAt) return null;
  return { hour: h, minute: m };
}

export const startOfToday = () => {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d;
};
