import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { DeliveryStatus, Zone } from '@/types';

export const DELIVERIES_QUERY_KEY = ['deliveries'] as const;
export const ACTIVE_ZONES_QUERY_KEY = ['active_zones'] as const;

export interface DeliveriesFilters {
  query?: string;
  status?: DeliveryStatus | '';
  date?: string;
  driverId?: string;
  zoneId?: string;
  quickView?: 'all' | 'needsPinning' | 'unassigned' | 'inTransit' | 'completed' | 'failed';
}

export interface PinDropoffPayload {
  lat: number;
  lng: number;
  dropoffAddress: string;
  dropoffCity: string;
  dropoffPostalCode: string;
}

export function useDeliveries(params: {
  page: number;
  size: number;
  status?: string | string[];
  date?: string;
  dateFrom?: string;
  dateTo?: string;
  driverId?: string | string[];
  zoneId?: string | string[];
  depot?: string | string[];
  unpinned?: string;
  /** NORMAL | HIGH — repeatable, matching the backend's `priority` request param. */
  priority?: string[];
  kind?: string | string[];
  /** "false" narrows to deliveries with no driver — the quick view, answered by the server. */
  assigned?: string;
  /** OVERDUE | TODAY | FUTURE — scheduled-date bucket, pending deliveries only. */
  bucket?: string;
}) {
  return useQuery({
    queryKey: ['deliveries', params],
    queryFn: async () => {
      const res = await api.get('/admin/deliveries', { params });
      return res.data;
    },
    retry: 1,
    staleTime: 30000,
  });
}

/** Quick-view tallies for the deliveries table.
 *
 *  Deliberately a separate call from the page of rows: the table is paginated server-side, so
 *  counting the loaded rows answers "how many on this page", which is never the question the
 *  chips are asking. The endpoint tallies across the whole matching dataset.
 *
 *  Takes the same base filters as the list — driver, date, zone, search — but not the quick view
 *  itself, since a chip that only counted its own selection would always read like the row count.
 */
export function useDeliveryCounts(params: {
  driverId?: string;
  date?: string;
  zoneId?: string;
  q?: string;
}) {
  return useQuery<Record<string, number>>({
    queryKey: ['delivery-counts', params],
    queryFn: async () => {
      const res = await api.get('/admin/deliveries/counts', { params });
      return (res.data ?? {}) as Record<string, number>;
    },
    retry: 1,
    staleTime: 30000,
  });
}

export function useActiveZones() {
  return useQuery<Zone[]>({
    queryKey: ACTIVE_ZONES_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<Zone[]>('/zones/active');
      return Array.isArray(res.data) ? res.data : [];
    },
    retry: 1,
    staleTime: 30000,
  });
}

export function usePinDropoff() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ deliveryId, payload }: { deliveryId: string; payload: PinDropoffPayload }) => {
      const res = await api.post(`/admin/deliveries/${deliveryId}/pin-dropoff`, payload);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DELIVERIES_QUERY_KEY });
    },
  });
}

export function useCancelDelivery() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ deliveryId, reason }: { deliveryId: string; reason: string }) => {
      const res = await api.post(`/admin/deliveries/${deliveryId}/cancel`, null, {
        params: { reason }
      });
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DELIVERIES_QUERY_KEY });
    },
  });
}
