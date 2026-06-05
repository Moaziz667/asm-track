import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/**
 * Reassign a failed/exception delivery to another driver (or route).
 * Real endpoint — POST /api/admin/ops/exceptions/{deliveryId}/reassign.
 * Mirrors the backend AdminExceptionReassignRequest contract.
 */
export interface ReassignExceptionPayload {
  deliveryId: string;
  driverId?: string;
  targetRouteId?: string;
  insertAtOrder?: number;
  startTimeWindow?: string; // HH:mm
  endTimeWindow?: string;   // HH:mm
  note?: string;
}

export function useReassignException() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ deliveryId, ...body }: ReassignExceptionPayload) => {
      const res = await api.post(`/api/admin/ops/exceptions/${deliveryId}/reassign`, body);
      return res.data;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['deliveries'] });
      qc.invalidateQueries({ queryKey: ['ops'] });
      qc.invalidateQueries({ queryKey: ['routes'] });
    },
  });
}

/**
 * Replay parked ERP-sync commands after the root cause is fixed.
 * Real endpoint — POST /api/admin/dlq/{queue}/replay (ADMIN only).
 * Returns how many messages were re-published to the original exchange.
 */
export function useReplayErpSync() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const res = await api.post('/api/admin/dlq/erp.sync.command.dlq/replay');
      return res.data as { queue: string; replayed: number };
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['deliveries'] });
    },
  });
}
