/**
 * Mirror of backend RouteReportResponse.java.
 * Keep field names in sync — JSON pass-through, no transform.
 */

export type StopClassification =
  | 'ON_TIME' | 'LATE' | 'EARLY'
  | 'PARTIAL' | 'FAILED' | 'FAILED_ATTEMPT'
  | 'REPLANNED' | 'CANCELLED' | 'PENDING';

export interface RouteReport {
  header: {
    routeId: string;
    routeName: string | null;
    date: string | null;          // ISO date
    driverId: string | null;
    driverName: string | null;
    vehicleId: string | null;
    vehiclePlate: string | null;
    vehicleType: string | null;
    depotName: string | null;
    status: string | null;
    startedAt: string | null;     // ISO datetime
    closedAt: string | null;
    durationMinutes: number | null;
    plannedStartTime: string | null;
    plannedEndTime: string | null;
  };
  kpis: {
    totalStopsPlanned: number;
    attemptedStops: number;
    completedStops: number;
    partialStops: number;
    failedStops: number;
    failedAttemptStops: number;
    replannedStops: number;
    cancelledStopsCount: number;
    completionRate: number;       // %
    onTimeRate: number;           // %
    lateStops: number;
    earlyStops: number;
    onTimeStops: number;
    cumulativeDelayMinutes: number | null;
    totalDistanceKm: number | null;
    activeDurationMinutes: number | null;
    routeStartDelayMinutes: number | null;
  };
  statusBreakdown: Array<{
    label: string;
    key: 'COMPLETED' | 'PARTIAL' | 'FAILED_ALL' | 'REPLANNED' | 'CANCELLED';
    count: number;
    percentage: number;
  }>;
  timeline: Array<{
    stopOrder: number;
    clientName: string | null;
    plannedEnd: string | null;
    actualAt: string | null;
    delayMinutes: number | null;
    classification: StopClassification | null;
  }>;
  stops: Array<{
    stopId: string;
    deliveryId: string;
    stopOrder: number;
    orderRef: string | null;
    clientName: string | null;
    address: string | null;
    city: string | null;
    startTimeWindow: string | null;
    endTimeWindow: string | null;
    arrivedAt: string | null;
    completedAt: string | null;
    delayMinutes: number | null;
    dwellMinutes: number | null;
    completionStatus: 'OK' | 'KO' | null;
    finalStatus: string;
    classification: StopClassification | null;
    hasPod: boolean;
    movement: 'REPLANNED' | 'CANCELLED' | 'HANDOFF' | null;
    movementTarget: string | null;
    removedAt: string | null;
    removedReason: string | null;
    removedBy: string | null;
    handoffConfirmedAt: string | null;
    handoffFromDriverName: string | null;
    handoffToDriverName: string | null;
    failureCode: string | null;
    failReason: string | null;
  }>;
  movements: Array<{
    at: string | null;
    type: 'STOP_REMOVED_REPLANNED' | 'STOP_REMOVED_CANCELLED' | 'HANDOFF_CONFIRMED'
        | 'STOP_FAILED' | 'DELIVERY_CANCELLED';
    stopOrder: number | null;
    clientName: string | null;
    actor: string | null;
    detail: string | null;
  }>;
  geometry: string | null;        // JSON [[lat,lng]...]
  podGallery: Array<{
    stopId: string;
    deliveryId: string;
    stopOrder: number;
    clientName: string | null;
    photoUrl: string | null;
    signatureUrl: string | null;
    bonLivraisonUrl: string | null;
    lat: number | null;
    lng: number | null;
    collectedAt: string | null;
    comment: string | null;
  }>;
  auditTrail: Array<{
    at: string | null;
    stopOrder: number | null;
    orderRef: string | null;
    actor: string | null;
    role: string | null;
    actionKey: string | null;   // raw event key — mapped to a localized label client-side
    action: string | null;      // backend-localized label (fallback when actionKey is unknown/absent)
    detailParams: Record<string, string> | null;  // resolved values (route/driver/reason/note) — labelled client-side
    detail: string | null;      // backend-localized detail string (fallback)
  }>;
  generatedAt: string | null;
}
