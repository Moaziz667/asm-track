import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import type { Delivery, Zone } from '@/types';

export const DELIVERIES_QUERY_KEY = ['deliveries'] as const;
export const ACTIVE_ZONES_QUERY_KEY = ['active_zones'] as const;

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
  status?: string;
  date?: string;
  driverId?: string;
  zoneId?: string;
  unpinned?: string;
}) {
  return useQuery({
    queryKey: ['deliveries', params],
    queryFn: async () => {
      const res = await api.get('/api/admin/deliveries', { params });
      return res.data;
    },
    retry: 1,
    staleTime: 30000,
  });
}

export function useActiveZones() {
  return useQuery<Zone[]>({
    queryKey: ACTIVE_ZONES_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<Zone[]>('/api/v1/zones/active');
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
      const res = await api.post(`/api/admin/deliveries/${deliveryId}/pin-dropoff`, payload);
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DELIVERIES_QUERY_KEY });
    },
  });
}

export function useCreateBackorder() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (deliveryId: string) => {
      const res = await api.post(`/api/admin/deliveries/${deliveryId}/create-backorder`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successBackorderCreated');
      queryClient.invalidateQueries({ queryKey: DELIVERIES_QUERY_KEY });
    },
    onError: (err: any) => {
      showErrorToast(err, 'errorBackorderFailed');
    }
  });
}

export function useCancelDelivery() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ deliveryId, reason }: { deliveryId: string; reason: string }) => {
      const res = await api.post(`/api/admin/deliveries/${deliveryId}/cancel`, null, {
        params: { reason }
      });
      return res.data;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: DELIVERIES_QUERY_KEY });
    },
  });
}
