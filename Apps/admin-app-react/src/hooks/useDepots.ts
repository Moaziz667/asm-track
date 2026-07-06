import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import type { Depot } from '@/types';

// Query keys constant
export const DEPOTS_QUERY_KEY = ['depots'] as const;

/** Result of an ERP depot sync (mirrors DepotSyncService.SyncResult). */
export interface DepotSyncResult {
  total: number;
  created: number;
  updated: number;
  geocoded: number;
  missingCoords: number;
}

export function useDepots() {
  return useQuery<Depot[]>({
    queryKey: DEPOTS_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<Depot[]>('/api/v1/depots');
      return Array.isArray(res.data) ? res.data : [];
    },
    retry: 1,
    staleTime: 30000, // 30 seconds
  });
}

/**
 * Sync depots from the ERP warehouses (Odoo stock.warehouse). Depots are ERP-owned —
 * they are no longer created/edited/deleted from the admin UI.
 */
export function useSyncDepotsFromErp() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async () => {
      const res = await api.post<DepotSyncResult>('/api/v1/depots/sync');
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDepotSynced');
      queryClient.invalidateQueries({ queryKey: DEPOTS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorDepotSyncFailed');
    },
  });
}

export interface PatchDepotLocationPayload {
  latitude?: number;
  longitude?: number;
  address?: string;
}

export function usePatchDepotLocation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ depotId, payload }: { depotId: string; payload: PatchDepotLocationPayload }) => {
      const res = await api.patch(`/api/v1/depots/${depotId}/location`, payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDepotUpdated');
      queryClient.invalidateQueries({ queryKey: DEPOTS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorDepotUpdateFailed');
    },
  });
}

/** Geocode a single depot's address via the backend Nominatim proxy. */
export function useGeolocateDepot() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (depotId: string) => {
      const res = await api.post(`/api/v1/depots/${depotId}/geolocate`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDepotGeolocate');
      queryClient.invalidateQueries({ queryKey: DEPOTS_QUERY_KEY });
    },
    onError: (err: unknown) => {
      showErrorToast(err, 'errorDepotGeolocateFailed');
    },
  });
}
