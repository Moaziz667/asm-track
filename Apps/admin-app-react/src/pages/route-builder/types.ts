import type { Driver } from '@/types';

export type RouteStop = {
  id: string;
  deliveryId: string;
  stopOrder: number;
  startTimeWindow?: string;
  endTimeWindow?: string;
  bufferMinutes?: number;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPinned?: boolean;
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
  totalWeightKg?: number;
  totalQuantity?: number;
  itemsSummary?: string;
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
