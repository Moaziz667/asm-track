import { api } from '@/lib/api';

/** Where a handover has got to. Mirrors the backend enum. */
export type CashRemittanceStatus = 'OPEN' | 'DECLARED' | 'RECEIVED' | 'DISPUTED' | 'RECONCILED';

export interface CashRemittance {
  id: string;
  driverId: string;
  driverName?: string;
  status: CashRemittanceStatus;
  /** What the platform knows the driver took. Neither party can move it. */
  expectedTotal: number;
  /** What the driver says he is handing over. */
  declaredTotal?: number;
  /** What the depot counted. */
  receivedTotal?: number;
  /** received − expected. Negative means money is missing. */
  discrepancy?: number;
  declaredAt?: string;
  receivedAt?: string;
  receivedByName?: string;
  reconciledAt?: string;
  /** What the cashier observed while counting. Distinct from [note], which settles a discrepancy. */
  countNote?: string;
  note?: string;
  openedAt?: string;
  closedAt?: string;
}

export interface CashCollectionRow {
  id: string;
  deliveryId: string;
  /** The number the counter argues about — "SFX/OUT/00306, il manque 200 dinars". */
  blNumber?: string;
  clientName?: string;
  amountExpected: number;
  amountCollected: number;
  method: 'CASH' | 'CHEQUE' | 'NONE';
  chequeNumber?: string;
  chequeBank?: string;
  status: 'PENDING' | 'COLLECTED' | 'PARTIAL' | 'REFUSED';
  reasonLabel?: string;
  collectedAt?: string;
}

export interface DriverOutstanding {
  driverId: string;
  amount: number;
  collections: number;
}

interface Page<T> { content: T[]; totalPages: number; totalElements: number }

/**
 * Handovers waiting on a human.
 *
 * Omitting `status` returns DECLARED + DISPUTED, which is the desk's real job. Asking for
 * everything is possible but is a report, not a worklist.
 */
export const listRemittances = (
  params: { status?: CashRemittanceStatus; page?: number; size?: number; sort?: string },
) => api.get<Page<CashRemittance>>('/admin/cash/remittances', { params }).then(r => r.data);

/** How many handovers sit in each state — every state, zeros included. */
export const getRemittanceCounts = () =>
  api.get<Record<CashRemittanceStatus, number>>('/admin/cash/remittances/counts').then(r => r.data);

/** The headline figure: everything collected and not yet handed over. */
export const getCirculation = () =>
  api.get<{ amount: number; currency: string }>('/admin/cash/circulation').then(r => r.data);

export const getOutstandingByDriver = () =>
  api.get<DriverOutstanding[]>('/admin/cash/outstanding-by-driver').then(r => r.data);

export const getRemittanceCollections = (id: string) =>
  api.get<CashCollectionRow[]>(`/admin/cash/remittances/${id}/collections`).then(r => r.data);

/** Record what was counted. The backend rejects the driver and the declaring party. */
export const receiveRemittance = (id: string, receivedTotal: number, note?: string) =>
  api.post<CashRemittance>(`/admin/cash/remittances/${id}/receive`, { receivedTotal, note }).then(r => r.data);

/** Close a handover whose figures disagreed. The note is mandatory server-side too. */
export const reconcileRemittance = (id: string, note: string) =>
  api.post<CashRemittance>(`/admin/cash/remittances/${id}/reconcile`, { note }).then(r => r.data);
