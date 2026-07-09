import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { FailureReason } from '@/types';

export const FAILURE_REASONS_QUERY_KEY = ['failure-reasons'] as const;

export function useFailureReasons() {
  return useQuery<FailureReason[]>({
    queryKey: FAILURE_REASONS_QUERY_KEY,
    queryFn: async () => {
      const res = await api.get<FailureReason[]>('/api/admin/failure-reasons');
      return Array.isArray(res.data) ? res.data : [];
    },
    retry: 1,
    staleTime: 60_000,
  });
}
