import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';

// ── Types ──────────────────────────────────────────────────────────────────────

export interface RouteStop {
  id: string;
  stopOrder: number;
  status: string;
  clientName?: string;
  deliveryAddress?: string;
  deliveryCity?: string;
  orderRef?: string;
  deliveryId?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  stopType?: 'PICKUP' | 'DELIVERY';
  sourceDepotId?: string | null;
  sourceDepotName?: string | null;
  sourceDepotLat?: number | null;
  sourceDepotLng?: number | null;
}

export interface RouteItem {
  id: string;
  name: string;
  driverName?: string;
  driverId?: string;
  date: string;
  status: string;
  city?: string;
  stops: RouteStop[];
  totalStops?: number;
  completedStops?: number;
  failedStops?: number;
  partialStops?: number;
  pendingStops?: number;
  progressPercent?: number;
  totalDurationSeconds?: number;
  totalDistanceMeters?: number;
  depotName?: string;
}

export interface RoutesQueryParams {
  from?: string;
  to?: string;
  status?: string;
  driverId?: string;
  page?: number;
  size?: number;
}

// ── Query Keys ─────────────────────────────────────────────────────────────────

export const ROUTES_QUERY_KEY = (params?: RoutesQueryParams) =>
  params ? (['routes', params] as const) : (['routes'] as const);

// ── Hooks ──────────────────────────────────────────────────────────────────────

/**
 * Fetch routes with optional date-range / status / driver filters.
 *
 */
export function useRoutes(params?: RoutesQueryParams, enabled = true) {
  return useQuery<RouteItem[]>({
    queryKey: ROUTES_QUERY_KEY(params),
    queryFn: async () => {
      const res = await api.get<RouteItem[]>('/admin/routes', { params });
      return Array.isArray(res.data) ? res.data : [];
    },
    enabled,
    retry: 1,
    staleTime: 30_000,
  });
}

/**
 * Fetch a single route by ID.
 */
export function useRoute(routeId: string | null | undefined) {
  return useQuery<RouteItem>({
    queryKey: ['route', routeId],
    queryFn: async () => {
      const res = await api.get<RouteItem>(`/admin/routes/${routeId}`);
      return res.data;
    },
    enabled: Boolean(routeId),
    retry: 1,
    staleTime: 20_000,
  });
}

/**
 * Reassign a route to a different driver.
 */
export function useReassignRoute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ routeId, driverId }: { routeId: string; driverId: string }) => {
      const res = await api.post(`/admin/routes/${routeId}/reassign`, { driverId });
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successRouteReassigned');
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorRouteReassignFailed');
    },
  });
}

/**
 * Close a route (transition to CLOSED status).
 */
export function useCloseRoute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (routeId: string) => {
      const res = await api.post(`/admin/routes/${routeId}/close`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successRouteClosed');
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorRouteClosureFailed');
    },
  });
}

/**
 * Cancel a committed route (VALIDATED/IN_PROGRESS → CANCELLED). Re-pools undelivered stops; the
 * reason is mandatory and recorded on the route + each delivery's history.
 */
export function useCancelRoute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ routeId, reason }: { routeId: string; reason: string }) => {
      const res = await api.post(`/admin/routes/${routeId}/cancel`, null, { params: { reason } });
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successRouteCancelled');
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorRouteCancelFailed');
    },
  });
}

/**
 * Validate a route (transition to VALIDATED status).
 */
export function useValidateRoute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (routeId: string) => {
      const res = await api.post(`/admin/routes/${routeId}/validate`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successRouteValidated');
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorRouteValidateFailed');
    },
  });
}
