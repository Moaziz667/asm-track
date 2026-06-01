import type { DeliveryStatus } from '@/types';

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
  comment?: string;
  returnToOrigin?: boolean;
  createdAt?: string;
  updatedAt?: string;
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
export type DispatchTab = 'assign' | 'action' | 'failed' | 'gps' | 'handoff';

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
  tokenExpiresAt?: string;
  confirmedAt?: string;
  reason?: string;
};
