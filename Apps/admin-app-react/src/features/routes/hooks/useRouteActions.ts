
import { useState, useCallback } from 'react';
import { api } from '@/lib/api';
import { toast } from '@/lib/toast';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { messages } from '@/lib/toast-messages';
import type { RouteItem } from './useRoutes';

interface UseRouteActionsOptions {
  onSuccess?: () => void;
  onOptimisticUpdate?: (id: string, patch: Partial<RouteItem>) => void;
  onOptimisticRemove?: (id: string) => void;
}

/**
 * All route mutation actions in one hook.
 * Handles: cancel, reassign, validate, close, delete draft.
 *
 * Optimistic updates: status changes are applied immediately,
 * rolled back on failure.
 */
export function useRouteActions({
  onSuccess,
  onOptimisticUpdate,
  onOptimisticRemove,
}: UseRouteActionsOptions = {}) {
  // ── Loading states ──────────────────────────────────────────────────
  const [cancellingId, setCancellingId] = useState<string | null>(null);
  const [reassigningId, setReassigningId] = useState<string | null>(null);
  const [validatingId, setValidatingId] = useState<string | null>(null);
  const [closingId, setClosingId] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  // ── Reassign modal state ────────────────────────────────────────────
  const [reassignRoute, setReassignRoute] = useState<RouteItem | null>(null);

  const openReassign = useCallback((route: RouteItem) => {
    setReassignRoute(route);
  }, []);

  const closeReassign = useCallback(() => {
    setReassignRoute(null);
  }, []);

  // ── Cancel modal state ──────────────────────────────────────────────
  const [cancelRoute, setCancelRoute] = useState<RouteItem | null>(null);
  const [cancelReason, setCancelReason] = useState('');

  const openCancel = useCallback((route: RouteItem) => {
    setCancelRoute(route);
    setCancelReason('');
  }, []);

  const closeCancel = useCallback(() => {
    setCancelRoute(null);
    setCancelReason('');
  }, []);

  // ── Actions ─────────────────────────────────────────────────────────

  const confirmCancel = useCallback(async () => {
    if (!cancelRoute) return;
    const { id } = cancelRoute;

    // Optimistic update
    onOptimisticUpdate?.(id, { status: 'CANCELLED' });

    try {
      setCancellingId(id);
      await api.post(
        `/api/admin/routes/${id}/cancel`,
        null,
        { params: { reason: cancelReason || undefined } },
      );
      const msg = messages.routes.cancelSuccess;
      toast.success(msg.title, { description: msg.description });
      closeCancel();
      onSuccess?.();
    } catch (err: any) {
      // Rollback
      onOptimisticUpdate?.(id, { status: cancelRoute.status });
      const msg = messages.routes.cancelFailed;
      toast.error(msg.title, { description: msg.description });
    } finally {
      setCancellingId(null);
    }
  }, [cancelRoute, cancelReason, onOptimisticUpdate, onSuccess, closeCancel]);

  const confirmReassign = useCallback(async (payload: any) => {
    if (!reassignRoute) return;
    const { id, stops, driverId } = reassignRoute;

    // Default to all active stops if it's a route-level reassign action
    const stopIds = stops?.map((s: any) => s.id) || [];
    if (stopIds.length === 0) {
      const msg = messages.deliveries.reassignFailed;
      toast.error(msg.title, { description: msg.description });
      return;
    }

    try {
      setReassigningId(id);
      await api.post(`/api/admin/routes/transfer-stops`, {
        sourceRouteId: id,
        targetRouteId: payload.targetType === 'route' ? payload.targetId : null,
        targetDriverId: payload.targetType === 'driver' ? payload.targetId : null,
        stopIds,
        reason: payload.note,
        acknowledgeWarnings: payload.acknowledgeWarnings || false,
      });
      const msg = messages.deliveries.reassignSuccess;
      toast.success(msg.title, { description: msg.description });
      closeReassign();
      onSuccess?.();
    } catch (err: any) {
      if (err?.response?.status === 422) {
        const msg = messages.routes.capacityWarning;
        toast.error(msg.title, { description: msg.description });
      } else {
        const msg = messages.deliveries.reassignFailed;
        toast.error(msg.title, { description: msg.description });
      }
    } finally {
      setReassigningId(null);
    }
  }, [reassignRoute, onSuccess, closeReassign]);

  const validateRoute = useCallback(async (route: RouteItem) => {
    const missingPins = route.stops.filter((s) => {
      if (typeof s.dropoffPinned === 'boolean') return !s.dropoffPinned;
      return s.dropoffLat == null || s.dropoffLng == null;
    }).length;

    if (missingPins > 0) {
      const msg = messages.routes.missingGPS;
      toast.error(msg.title, { description: msg.description });
      return;
    }

    try {
      setValidatingId(route.id);
      await api.put(`/api/admin/routes/${route.id}/validate`);
      const msg = messages.routes.validationSuccess;
      toast.success(msg.title, { description: msg.description });
      onSuccess?.();
    } catch (err: any) {
      const msg = messages.routes.validationFailed;
      toast.error(msg.title, { description: msg.description });
    } finally {
      setValidatingId(null);
    }
  }, [onSuccess]);

  const closeRoute = useCallback(async (route: RouteItem) => {
    try {
      setClosingId(route.id);
      await api.post(`/api/admin/routes/${route.id}/close`);
      const msg = messages.routes.closeSuccess;
      toast.success(msg.title, { description: msg.description });
      onSuccess?.();
    } catch (err: any) {
      const msg = messages.routes.closeFailed;
      toast.error(msg.title, { description: msg.description });
    } finally {
      setClosingId(null);
    }
  }, [onSuccess]);

  const deleteDraft = useCallback(async (route: RouteItem) => {
    // Optimistic remove
    onOptimisticRemove?.(route.id);

    try {
      setDeletingId(route.id);
      await api.delete(`/api/admin/routes/${route.id}`);
      const msg = messages.routes.deleteSuccess;
      toast.success(msg.title, { description: msg.description });
      onSuccess?.();
    } catch (err: any) {
      const msg = messages.routes.deleteFailed;
      toast.error(msg.title, { description: msg.description });
      onSuccess?.(); // re-fetch to restore
    } finally {
      setDeletingId(null);
    }
  }, [onOptimisticRemove, onSuccess]);

  return {
    // Cancel flow
    cancelRoute,
    cancelReason,
    setCancelReason,
    openCancel,
    closeCancel,
    confirmCancel,
    cancellingId,

    // Reassign flow
    reassignRoute,
    openReassign,
    closeReassign,
    confirmReassign,
    reassigningId,

    // Other actions
    validateRoute,
    validatingId,
    closeRoute,
    closingId,
    deleteDraft,
    deletingId,
  };
}
