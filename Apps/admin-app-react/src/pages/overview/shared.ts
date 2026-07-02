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

export const STATUS_COLOR: Record<string, string> = {
  UNSCHEDULED: '#C4881A', SCHEDULED: '#5E6AD2', PICKED_UP: '#2594B8', IN_TRANSIT: '#D4772C',
  DELIVERED: '#4CAF82', PARTIALLY_DELIVERED: '#7B6FCC', FAILED: '#C7372F', CANCELLED: '#8A8F98',
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
