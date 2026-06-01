
import { useState, useCallback, useEffect } from 'react';
import { api } from '@/lib/api';
import { toast } from '@/lib/toast';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { messages } from '@/lib/toast-messages';

export type RouteStatus = 'DRAFT' | 'VALIDATED' | 'IN_PROGRESS' | 'CLOSED' | 'CANCELLED';

export interface RouteStop {
  id: string;
  deliveryId: string;
  stopOrder: number;
  status: string;
  deliveryAddress?: string;
  deliveryCity?: string;
  deliveryPostalCode?: string;
  deliveryCountryCode?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPinned?: boolean;
  clientName?: string;
  clientPhone?: string;
  orderRef?: string;
  etaAt?: string;
  slaDeadline?: string;
  totalAmount?: number;
  deliveryStatus?: string;
  // Time window fields (editor + validation)
  startTimeWindow?: string;
  endTimeWindow?: string;
  bufferMinutes?: number;
  routeGeometry?: string;
  completionStatus?: string;
}

export interface RouteItem {
  id: string;
  name: string;
  driverId: string;
  driverName?: string;
  vehicleId?: string;
  date: string;
  plannedStartTime?: string;
  plannedEndTime?: string;
  city?: string;
  status: RouteStatus;
  stops: RouteStop[];
  depotId?: string;
  detectedZoneLabel?: string;
  detectedZoneNames?: string[];
  validationWarnings?: string[];
  validatedAt?: string;
  startedAt?: string;
  cumulativeDelayMinutes?: number;
  onTimeCompletionRate?: number;
  routeOnTimeCompletionRate?: number;
  totalDurationSeconds?: number;
  totalDistanceMeters?: number;
  isOptimized?: boolean;
  legacyStops?: RouteStop[];
  routeVersion?: number;
}

export interface RoutesFilters {
  status?: string;
  city?: string;
  dateFrom?: string;
  dateTo?: string;
}

/**
 * Data hook for the /routes page.
 * Handles: fetch, filter, refresh, pagination, search.
 * Keeps route list in sync after mutations.
 */
export function useRoutes(initialFilters: RoutesFilters = {}) {
  const [routes, setRoutes] = useState<RouteItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [filters, setFilters] = useState<RoutesFilters>(initialFilters);

  const fetchRoutes = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true);
    else setLoading(true);

    try {
      const params: Record<string, string> = {};
      if (filters.status) params.status = filters.status;
      if (filters.city?.trim()) params.city = filters.city.trim();
      if (filters.dateFrom) params.from = filters.dateFrom;
      if (filters.dateTo) params.to = filters.dateTo;

      const res = await api.get('/api/admin/routes', { params });
      setRoutes(Array.isArray(res.data) ? res.data : []);
    } catch {
      if (!silent) {
        const msg = messages.routes.loadFailed;
        toast.error(msg.title, { description: msg.description });
      }
      setRoutes([]);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [filters]);

  // Refresh on filter changes
  useEffect(() => {
    void fetchRoutes();
  }, [fetchRoutes]);

  const refresh = useCallback(() => void fetchRoutes(), [fetchRoutes]);
  const silentRefresh = useCallback(() => void fetchRoutes(true), [fetchRoutes]);

  /** Optimistic local update — immediately update a single route in state */
  const updateRouteLocally = useCallback((id: string, patch: Partial<RouteItem>) => {
    setRoutes((prev) => prev.map((r) => r.id === id ? { ...r, ...patch } : r));
  }, []);

  /** Remove a route from local state (after delete) */
  const removeRouteLocally = useCallback((id: string) => {
    setRoutes((prev) => prev.filter((r) => r.id !== id));
  }, []);

  return {
    routes,
    loading,
    refreshing,
    filters,
    setFilters,
    refresh,
    silentRefresh,
    updateRouteLocally,
    removeRouteLocally,
  };
}
