import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/ui/toast-service';
import type { Zone } from '@/types';

export const ZONES_QUERY_KEY = ['zones'] as const;

export interface ZonePayload {
  name: string;
  color?: string | null;
  description?: string | null;
  cities?: string[];
  postalCodes: string[];
  isActive: boolean;
  geometry?: string | null;
}

export function useZones() {
  return useQuery<Zone[]>({
    queryKey: ZONES_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<Zone[]>('/zones');
      return Array.isArray(res.data) ? res.data : [];
    },
    retry: 1,
    staleTime: 30000,
  });
}

export function useCreateZone() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (payload: ZonePayload) => {
      const res = await api.post<Zone>('/zones', payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successZoneCreated');
      queryClient.invalidateQueries({ queryKey: ZONES_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorZoneCreateFailed');
    }
  });
}

export function useUpdateZone() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: ZonePayload }) => {
      const res = await api.put<Zone>(`/zones/${id}`, payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successZoneUpdated');
      queryClient.invalidateQueries({ queryKey: ZONES_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorZoneUpdateFailed');
    }
  });
}

export function useDeleteZone() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.delete<void>(`/zones/${id}`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successZoneDeleted');
      queryClient.invalidateQueries({ queryKey: ZONES_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorZoneDeleteFailed');
    }
  });
}

export function useSyncZones() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async () => {
      const res = await api.post<void>('/admin/deliveries/sync-zones');
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successZonesSynced');
      queryClient.invalidateQueries({ queryKey: ZONES_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorZonesSyncFailed');
    }
  });
}
