// Shared shapes for the reassign / assign drawer module.

/** A delivery being (re)assigned. Carries just enough context for the driver picker,
 *  the placement recap, and pre-filling the window from the client's slot. */
export interface ReassignTarget {
  deliveryId: string;
  orderRef?: string;
  erpOrderId?: string;
  clientName?: string;
  city?: string;
  status: string;
  driverName?: string;
  routeId?: string;
  routeName?: string;
  routeStatus?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  // Existing delivery time slot (the client's créneau) — used to pre-fill the window picker.
  timeSlotStartTime?: string;
  timeSlotEndTime?: string;
  // Fallbacks when there's no HH:mm slot: a named slot, or the requested delivery date.
  timeSlotName?: string;
  requestedDeliveryDate?: string;
  // Decision context shown in the drawer recap.
  totalWeightKg?: number;
  totalAmount?: number;
  currency?: string;
  itemsCount?: number;
  priority?: string;
  scheduledAt?: string;
  dropoffAddress?: string;
}

export interface NearestInfo { etaSeconds: number | null; distanceMeters: number | null; rank: number; }

export interface Stop {
  id: string; deliveryId: string | null; stopOrder: number; status: string;
  startTimeWindow?: string; endTimeWindow?: string; clientName?: string; orderRef?: string; deliveryCity?: string;
  stopType?: string; // DELIVERY | PICKUP — pickups are depot loads, not insertion positions
  sourceDepotName?: string;
}

export interface RouteData { id: string; name: string; status: string; date: string; stops: Stop[]; payloadKg?: number; currentLoadKg?: number; }

export type Cfg = { start: string; end: string; order: number | null; touched: boolean };
