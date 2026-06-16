import type { DeliveryStatus, Delivery } from '@/types';

export type OpsException = {
  deliveryId: string;
  orderId?: string;
  orderRef?: string;
  routeId?: string;
  routeName?: string;
  routeStatus?: string;
  status: DeliveryStatus;
  failureCode?: string;
  motif: string;
  driverId?: string;
  driverName?: string;
  clientName?: string;
  city?: string;
  zoneName?: string;
  severity: string;
  slaPhase?: string;
  slaHealth?: string;
  comment?: string;
  returnToOrigin?: boolean;
  createdAt?: string;
  updatedAt?: string;
  scheduledAt?: string;
  dropoffLat?: number;
  dropoffLng?: number;
};

export type OpsExceptionResponse = {
  generatedAt: string;
  period: string;
  periodStart: string;
  periodEnd: string;
  total: number;
  items: OpsException[];
};

export type Period     = 'day' | 'week' | 'month' | 'custom' | 'all';
export type ActionKind = 'reassign' | 'replan';
export type DispatchTab = 'queue' | 'assign' | 'action' | 'failed' | 'gps' | 'handoff';

// Unified Queue row — a delivery awaiting attention, optionally carrying its active alert.
// Lets the split-view list+detail render both "needs assignment" and "needs action" rows
// with a single component instead of two parallel card grids (DeliveryCards/ActionCards).
export type QueueRow = {
  id: string;
  delivery: Delivery;
  alert?: OpsException;
  routeId?: string;
  routeName?: string;
};

export type PendingAction = { kind: ActionKind; row: OpsException };

// Mirrors the backend HandoffResponse (com.asm.delivery.dto.response.HandoffResponse).
export type HandoffState = 'REQUESTED' | 'IN_PROGRESS' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED';

export type HandoffItem = {
  id: string;
  state: HandoffState;
  deliveryId?: string;
  routeId?: string;
  erpOrderId?: string;
  clientName?: string;
  dropoffAddress?: string;
  fromDriverId?: string;
  fromDriverName?: string;
  toDriverId?: string;
  toDriverName?: string;
  requestedAt?: string;
  requestedBy?: string;
  inProgressAt?: string;
  tokenExpiresAt?: string;
  confirmedAt?: string;
  expiredAt?: string;
  cancelledAt?: string;
  cancelledBy?: string;
  reason?: string;
};
