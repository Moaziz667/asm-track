export type DeliveryStatus =
  | 'UNSCHEDULED'
  | 'SCHEDULED'
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'AWAITING_HANDOFF'
  | 'DELIVERED'
  | 'PARTIALLY_DELIVERED'
  | 'CANCELLED'
  | 'FAILED';

export type DeliverySource = 'ODOO' | 'DUX';

export type OrderSource = 'ODOO' | 'DUX';

export interface FailureReason {
  id: string;
  code: string;
  label: string;
  category: string;
  scope: 'DELIVERY' | 'ITEM' | 'BOTH';
  active: boolean;
  sortOrder: number;
}

export interface AnalyticsScope {
  zone?: string[];
  driverId?: string[];
  status?: DeliveryStatus[];
  motif?: string[];
  city?: string[];
  source?: OrderSource[];
  depot?: string[];
}

export interface DeliveryItem {
  id?: string;
  sku?: string;
  name: string;
  quantity: number;
  quantityDone?: number;
  price?: number;
  unitPrice?: number;
  unitWeightKg?: number;
  outcome?: 'DELIVERED' | 'REFUSED' | 'DAMAGED' | 'MISSING';
  reason?: string;
  /** Human label for `reason`, snapshotted from the failure-reason catalog at submission time. */
  reasonLabel?: string;
  comment?: string;
}

export interface Delivery {
  id: string;
  deliveryId?: string;
  routeId?: string;
  routeName?: string;
  /** Status of the route this delivery sits on (DRAFT/VALIDATED/IN_PROGRESS…) — backend summary already sends it. */
  routeStatus?: string;
  orderId: string;
  orderRef?: string;
  clientName: string;
  clientPhone?: string;
  dropoffAddress?: string;
  dropoffCity?: string;
  dropoffPostalCode?: string;
  /** Free text the driver acts on at the door. Served by the API and shown in the driver app. */
  deliveryInstructions?: string | null;
  dropoffCountryCode?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPinned?: boolean;
  zoneId?: string;
  zoneName?: string;
  zoneColor?: string;
  timeSlotId?: string;
  timeSlotName?: string;
  timeSlotStartTime?: string;
  timeSlotEndTime?: string;
  requestedDeliveryDate?: string;
  /** Effective scheduled date (replan date if rescheduled, else ERP date). */
  scheduledAt?: string;
  /** Replan date; non-null when the delivery was rescheduled (drives the "Reprogrammé" badge). */
  rescheduledAt?: string;
  /** Official ERP delivery-note (bon de livraison) number. */
  blNumber?: string;
  /** ERP source-warehouse code. */
  warehouseCode?: string;
  /**
   * ERP values the integrator mapped that ASM has no field of its own for.
   *
   * Display-only: whatever this customer keeps in their ERP, so nothing here can be sorted or
   * filtered on. Keys are the labels the integrator chose on the mapping screen.
   */
  customFields?: Record<string, unknown> | null;
  /** Resolved source depot (where goods are loaded). */
  sourceDepotId?: string;
  sourceDepotName?: string;
  status: DeliveryStatus;
  driverName?: string;
  driverId?: string;
  driverPhone?: string;
  items?: DeliveryItem[];
  totalAmount?: number;
  totalWeightKg?: number;
  routeGeometry?: string;
  routeDistanceKm?: number;
  routeDurationMinutes?: number;
  transitSlaMinutesComputed?: number;
  /** Unified SLA (source of truth) — set by the backend SlaState. */
  slaPhase?: 'PLANNING' | 'ASSIGNMENT' | 'DEPARTURE' | 'DELIVERY' | 'HANDOFF' | 'DELIVERED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
  slaHealth?: 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'MET' | 'LATE' | 'NONE';
  /** Worst health any phase ever reached (persisted) — reveals lateness even after a delivery
   *  failed (whose live slaHealth is NONE). Plus how late it ran, in minutes. */
  slaWorstHealth?: 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'MET' | 'LATE' | 'NONE';
  slaLateMinutes?: number;
  routeEtaAt?: string;
  routeProvider?: string;
  erpSyncStatus?: string;
  erpBackorderId?: number;
  source?: DeliverySource;
  erpId?: string;
  erpOrderId?: string;
  priority?: string;
  assignedAt?: string;
  completedAt?: string;
  inTransitAt?: string;
  createdAt: string;
  updatedAt?: string;
  failureReason?: string;
  currency?: string;
  /** Failure detail (driver motif + free comment) for FAILED/PARTIAL. */
  failureCode?: string;
  failReason?: string;
  failureComment?: string;
  /** Cancellation reason for CANCELLED. */
  cancelReason?: string;
  /** ERP grouping ref + sibling shipments for multi-parcel orders. */
  erpExternalRef?: string;
  relatedShipments?: Array<{ deliveryId: string; blNumber?: string; erpOrderId?: string; status: string; current: boolean }>;
  /** True when this delivery already has an open return (REQUESTED/APPROVED/RECEIVED). */
  hasOpenReturn?: boolean;
}

export type DriverAccountStatus = 'PENDING_SETUP' | 'ACTIVE' | 'SUSPENDED';

export interface Driver {
  id: string;
  name: string;
  phone: string;
  isRegistered?: boolean;
  accountStatus?: DriverAccountStatus;
  activeDeliveryId?: string;
  activeRouteId?: string;
  totalDeliveries?: number;
  delivered?: number;
  failed?: number;
  successRate?: number;
  currentLat?: number;
  currentLng?: number;
  lastLocationAt?: string;
  email?: string;
  createdAt?: string;
  photoUrl?: string;
  onlineStatus?: 'ONLINE' | 'ON_BREAK' | 'OFFLINE';
  invitationExpiresAt?: string | null;
  lastInvitedAt?: string | null;
  invitedByName?: string | null;
  suspendedReason?: string | null;
  location?: {
    lat: number;
    lng: number;
  };
}

export interface DashboardStats {
  today: {
    total: number;
    delivered: number;
    failed: number;
    /** Failure events later replanned (subset of `failed`) — recovery signal. */
    reprogrammed?: number;
    waiting?: number;
    unscheduled?: number;
    inTransit?: number;
    scheduled?: number;
    assigned?: number;
    pickedUp?: number;
    successRate: number;
    partialRate?: number;
    partialCount?: number;
    avgAssignToPickupMinutes?: number;
    avgPickupToTransitMinutes?: number;
    avgTransitToCompletionMinutes?: number;
    period?: 'day' | 'week' | 'month' | 'custom' | string;
    periodStart?: string;
    periodEnd?: string;
  };
  byDriver: Array<{
    driverName: string;
    driverId?: string;
    total: number;
    delivered: number;
    failed?: number;
    successRate?: number;
  }>;
  byFailureCode: Array<{
    code: string;
    count: number;
  }>;
  topItems?: Array<{
    sku: string;
    name: string;
    count: number;
  }>;
}

export interface AdminOpsOverview {
  generatedAt: string;
  period: string;
  periodStart: string;
  periodEnd: string;
  truncated?: boolean;
  /** Live dispatch churn since the start of the current business day. */
  reassignedToday?: number;
  replannedToday?: number;
  sla: {
    waitingThresholdMinutes: number;
    transitThresholdMinutes: number;
    waitingBreaches: number;
    transitBreaches: number;
    totalBreaches: number;
    slaAtRisk?: number;
    slaBreached?: number;
  };
  lanes: Array<{
    status: DeliveryStatus;
    label: string;
    count: number;
    items: Array<{
      deliveryId: string;
      orderId?: string;
      orderRef?: string;
      clientName?: string;
      city?: string;
      driverName?: string;
      createdAt?: string;
      routeId?: string;
    }>;
  }>;
  exceptions: Array<{
    deliveryId: string;
    orderId?: string;
    orderRef?: string;
    status: DeliveryStatus;
    clientName?: string;
    city?: string;
    driverName?: string;
    severity: string;
    message: string;
    createdAt?: string;
    routeId?: string;
  }>;
}

export interface TimelineEvent {
  id?: string;
  status: DeliveryStatus;
  timestamp: string;
  actor?: string;
  note?: string;
  changedAt?: string;
  changedBy?: string;
  changedByRole?: string;
  // Decoupled localization fields (Bringg-grade)
  eventKey?: string;
  eventParams?: Record<string, unknown> | string;
}

export interface ProofOfDelivery {
  signatureBase64?: string;
  photoBase64?: string;
  signatureUrl?: string;
  photoUrl?: string;
  comment?: string;
  timestamp?: string;
  collectedAt?: string;
  latitude?: number;
  longitude?: number;
  lat?: number;
  lng?: number;
}

export interface TrackingInfo {
  driverLat?: number;
  driverLng?: number;
  destinationLat?: number;
  destinationLng?: number;
  etaMinutes?: number;
  lastUpdated?: string;
}

export interface Client {
  id: string;
  name: string;
  phone: string;
  odooPartnerId?: number;
  verified: boolean;
  createdAt: string;
}

export interface AdminUser {
  id: string;
  name: string;
  email: string;
  role: string;
  active: boolean;
  createdAt: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export type AdminRole = 'ADMIN' | 'DISPATCHER' | 'MANAGER' | 'UNKNOWN';

// ── Zone ─────────────────────────────────────────────────────────────────────

export interface Zone {
  id: string;
  name: string;
  color?: string;
  description?: string;
  cities: string[];
  postalCodes: string[];
  isActive: boolean;
  geometry?: string;
  createdAt: string;
  updatedAt: string;
}

// ── Route zone detection (computed server-side) ───────────────────────────────

export interface RouteZoneInfo {
  /** Compound label, e.g. "Grand Tunis · Ariana". Empty string if none. */
  detectedZoneLabel: string;
  /** Individual zone names for multi-zone warning. */
  detectedZoneNames: string[];
}

export interface GeocodeSuggestion {
  lat: number;
  lng: number;
  displayName: string;
  /** Populated from reverse geocode only */
  city?: string;
  /** Populated from reverse geocode only */
  postalCode?: string;
  found: boolean;
  outsideTunisiaBbox?: boolean;
}

// ── Depot ────────────────────────────────────────────────────────────────────

export interface Depot {
  id: string;
  name: string;
  address?: string;
  /** ERP warehouse code this depot mirrors (the stable sync/resolution key). */
  warehouseCode?: string;
  /** ERP warehouse id (e.g. Odoo stock.warehouse id). */
  erpWarehouseId?: string;
  /** ERP provider that owns this depot (e.g. "odoo"). */
  provider?: string;
  /** Nullable until coordinates are read from Odoo or geocoded. */
  latitude?: number | null;
  longitude?: number | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

// ── Time Slots ──────────────────────────────────────────────────────────────

export interface TimeSlot {
  id: string;
  name: string;
  startTime: string;
  endTime: string;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

// ── SLA & Route optimization ─────────────────────────────────────────────────

export type SlaStatus = 'ON_TIME' | 'EARLY' | 'LATE';
export type AlertType = 'APPROACHING' | 'LATE';

export interface RouteAlert {
  alertId: string;
  stopId: string;
  stopOrder: number;
  alertType: AlertType;
  message: string;
  createdAt: string;
}

export interface RouteStopEta {
  stopId: string;
  sequenceOrder: number;
  deliveryAddress?: string;
  etaAt?: string;
  slaDeadline?: string;
  slaStatus?: SlaStatus;
  driveDurationSeconds?: number;
  driveDistanceMeters?: number;
  actualArrivalAt?: string;
  status: string;
  dwellMinutes?: number;
}

export interface OptimizeRouteResponse {
  optimizedStops: RouteStopEta[];
  totalDurationSeconds: number;
  totalDistanceMeters: number;
  savings: {
    durationSavedSeconds: number;
    distanceSavedMeters: number;
  };
}
