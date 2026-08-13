import type { DeliveryItem } from '@/types';

export type StopOrder = {
  id?: string;
  clientName?: string;
  clientPhone?: string;
  dropoffAddress?: string;
  totalAmount?: number;
  totalWeightKg?: number;
  totalQuantity?: number;
  priority?: string;
  source?: string;
  erpOrderId?: string;
  erpExternalRef?: string;
  deliveryInstructions?: string;
  currency?: string;
  items?: DeliveryItem[];
};

/**
 * One line to carry out of the depot at a pickup stop. Flat rather than grouped by delivery: the
 * order reference rides on every line, so a row read on its own still says whose goods it is.
 */
export type PickupLoadLine = {
  deliveryId?: string;
  orderRef?: string;
  clientName?: string;
  sku?: string;
  name?: string;
  quantity?: number;
};

export type RouteStop = {
  id: string;
  deliveryId: string;
  stopType?: 'PICKUP' | 'DELIVERY';
  stopOrder: number;
  status: string;
  arrivedAt?: string;
  completedAt?: string;
  notes?: string;
  deliveryAddress?: string;
  deliveryCity?: string;
  deliveryPostalCode?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPinned?: boolean;
  sourceDepotId?: string;
  sourceDepotName?: string;
  sourceDepotLat?: number;
  sourceDepotLng?: number;
  parcelCount?: number;
  pickupLoad?: PickupLoadLine[];
  routeGeometry?: string;
  routeDistanceKm?: number;
  routeDurationMinutes?: number;
  routeEtaAt?: string;
  startTimeWindow?: string;
  endTimeWindow?: string;
  slaStatus?: string;
  slaHealth?: string;
  slaPhase?: string;
  delayMinutes?: number;
  delayStatus?: string;
  delayReason?: string;
  transitSlaMinutesComputed?: number;
  order?: StopOrder;
  delivery?: import('@/types').Delivery & { order?: { referenceId?: string; erpOrderId?: string } };
  clientName?: string;
  removedAt?: string;
  removedReason?: string;
};

export type RouteDetail = {
  id: string;
  name: string;
  date: string;
  plannedStartTime?: string;
  plannedEndTime?: string;
  city?: string;
  status: 'DRAFT' | 'VALIDATED' | 'IN_PROGRESS' | 'CLOSED' | 'CANCELLED';
  createdAt?: string;
  createdBy?: string;
  validatedAt?: string;
  startedAt?: string;
  closedAt?: string;
  cumulativeDelayMinutes?: number;
  onTimeCompletionRate?: number;
  routeStartDelayMinutes?: number;
  totalStops?: number;
  completedStops?: number;
  failedStops?: number;
  partialStops?: number;
  pendingStops?: number;
  progressPercent?: number;
  totalDurationSeconds?: number;
  totalDistanceMeters?: number;
  isOptimized?: boolean;
  detectedZoneLabel?: string;
  routeGeometry?: string;
  departureTime?: string;
  stops: RouteStop[];
  driver?: { id: string; name?: string; phone?: string; available?: boolean; currentLat?: number; currentLng?: number };
  vehicle?: { id: string; name?: string; plate?: string; payloadKg?: number; type?: string };
  depot?: { id: string; name?: string; address?: string; city?: string; latitude?: number; longitude?: number };
  routeVersion?: number;
  legacyStops?: RouteStop[];
};
