import type {} from '@/types';

export type RouteStopType = 'PICKUP' | 'DELIVERY';

export type RouteStop = {
  id: string;
  /** Null at runtime for PICKUP stops; branch on stopType before using. */
  deliveryId: string;
  stopOrder: number;
  startTimeWindow?: string;
  endTimeWindow?: string;
  bufferMinutes?: number;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPinned?: boolean;
  // Multi-depot (slice 4/5)
  stopType?: RouteStopType;
  sourceDepotId?: string | null;
  /**
   * Every depot this stop loads from. A delivery whose lines sit in two warehouses belongs to two
   * pickups, which `sourceDepotId` alone cannot say — match on this, never on that.
   */
  sourceDepotIds?: string[] | null;
  /** PICKUP only: how many shipments are collected here, as the server counted them. */
  parcelCount?: number | null;
  /** PICKUP only: what to carry out of that depot. */
  pickupLoad?: { deliveryId?: string; orderRef?: string; clientName?: string; sku?: string; name?: string; quantity?: number }[] | null;
  sourceDepotName?: string | null;
  sourceDepotLat?: number | null;
  sourceDepotLng?: number | null;
  /**
   * The road path of the drive ending at this stop, as the optimiser computed it.
   *
   * <p>The API has always sent it; it was simply never declared here, so the builder's map had
   * nothing to draw a loading leg with and joined the two points in a straight line instead.
   * Absent until the route has been optimised.
   */
  routeGeometry?: string;
};

export type RouteItem = {
  id: string;
  name: string;
  date: string;
  status: string;
  plannedStartTime?: string;
  plannedEndTime?: string;
  depotId?: string;
  driverId?: string;
  driverName?: string;
  vehicleId?: string;
  vehicleName?: string;
  stops: RouteStop[];
  totalDistance?: number;
  totalDuration?: number;
  totalDistanceMeters?: number;
  totalDurationSeconds?: number;
  geometryPayload?: string;
  routeGeometry?: string;
  locked?: boolean;
};

export type DeliveryOption = {
  id: string;
  erpOrderId?: string;
  orderRef?: string;
  status: string;
  zoneId?: string;
  zoneName?: string;
  clientName?: string;
  dropoffAddress?: string;
  dropoffCity?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPostalCode?: string;
  dropoffCountryCode?: string;
  priority?: number;
  motif?: string;
  dropoffPinned?: boolean;
  createdAt?: string;
  scheduledAt?: string;
  totalWeightKg?: number;
  totalQuantity?: number;
  itemsSummary?: string;
  // Multi-depot sourcing (slice 5)
  warehouseCode?: string | null;
  sourceDepotId?: string | null;
  items?: Array<{
    id: string;
    sku?: string;
    name?: string;
    quantity: number;
    unitWeightKg?: number;
  }>;
};

export type VehicleItem = { 
  id: string; 
  name: string; 
  plate?: string; 
  payloadKg?: number; 
  active?: boolean;
  assigned?: boolean;
};

export type DepotItem = { 
  id: string; 
  name: string; 
  latitude: number; 
  longitude: number; 
};

export type StopWindowDraft = {
  startTime: string;
  endTime: string;
  buffer: string;
};

export type OptimizeSuggestion = {
  routeId: string;
  optimizedStops: Array<{
    stopId: string;
    deliveryId: string;
    sequenceOrder: number;
    etaAt?: string;
    clientName?: string;
    dropoffLat: number;
    dropoffLng: number;
    driveDurationSeconds?: number;
    driveDistanceMeters?: number;
  }>;
  totalDurationSeconds: number;
  totalDistanceMeters: number;
  routeGeometry?: string;
  savings?: {
    durationSavedSeconds?: number;
    distanceSavedMeters?: number;
  };
  distanceGain?: number;
};

export type RouteBuilderMapProps = {
  orders: DeliveryOption[];
  routes: RouteItem[];
  selectedOrderIds: string[];
  onSelectionChange: (ids: string[]) => void;
  highlightedRouteId?: string | null;
  onRouteClick?: (routeId: string) => void;
  onPinDragStart?: (orderId: string, orderIds: string[], clientName: string, e: MouseEvent) => void;
  /** Read synchronously in Leaflet click handler to suppress click after drag */
  getDragging?: () => boolean;
  suggestedStops?: Array<{
    stopId: string;
    deliveryId: string;
    sequenceOrder: number;
    etaAt?: string;
    clientName?: string;
    dropoffLat: number;
    dropoffLng: number;
  }>;
  suggestedRouteGeometry?: string;
  showRouteTrajet?: boolean;
  depot?: {
    name: string;
    latitude: number;
    longitude: number;
  } | null;
  mapLayer?: 'street' | 'satellite' | 'hot';
  showDepot?: boolean;
};
