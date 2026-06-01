import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import type { Depot } from '@/types';

// Query keys constant
export const DEPOTS_QUERY_KEY = ['depots'] as const;

export interface DepotPayload {
  name: string;
  address?: string;
  latitude: number;
  longitude: number;
  isActive: boolean;
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

export function useCreateDepot() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (payload: DepotPayload) => {
      const res = await api.post<Depot>('/api/v1/depots', payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDepotCreated');
      queryClient.invalidateQueries({ queryKey: DEPOTS_QUERY_KEY });
    },
    onError: (err: any) => {
      showErrorToast(err, 'errorDepotCreateFailed');
    }
  });
}

export function useUpdateDepot() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: Partial<DepotPayload> }) => {
      const res = await api.put<Depot>(`/api/v1/depots/${id}`, payload);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDepotUpdated');
      queryClient.invalidateQueries({ queryKey: DEPOTS_QUERY_KEY });
    },
    onError: (err: any) => {
      showErrorToast(err, 'errorDepotUpdateFailed');
    }
  });
}

export function useDeleteDepot() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (id: string) => {
      const res = await api.delete<void>(`/api/v1/depots/${id}`);
      return res.data;
    },
    onSuccess: () => {
      showSuccessToast('successDepotDeleted');
      queryClient.invalidateQueries({ queryKey: DEPOTS_QUERY_KEY });
    },
    onError: (err: any) => {
      showErrorToast(err, 'errorDepotDeleteFailed');
    }
  });
}
