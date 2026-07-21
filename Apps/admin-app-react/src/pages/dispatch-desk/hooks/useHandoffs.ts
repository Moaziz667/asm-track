import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { useNotificationsState } from '@/components/AlertsProvider';
import type { HandoffItem } from '../types';

/** Open handoff older than this (minutes) is treated as overdue/critical. */
export const HANDOFF_OVERDUE_MINUTES = 15;

/** Handoff events that should trigger a live refresh of the list. */
const HANDOFF_EVENTS = new Set([
  'handoff.requested',
  'handoff.overdue',
  'handoff.cancelled',
  'delivery.handoff_confirmed',
]);

export function isHandoffOpen(h: HandoffItem): boolean {
  return h.state === 'REQUESTED' || h.state === 'IN_PROGRESS';
}

/** Terminal handoffs (completed lifecycle) — kept as history in the dispatch desk. */
export function isHandoffTerminal(h: HandoffItem): boolean {
  return h.state === 'CONFIRMED' || h.state === 'EXPIRED' || h.state === 'CANCELLED';
}

/** Best timestamp to sort/age a terminal handoff by (when it ended). */
export function handoffEndedAt(h: HandoffItem): string | undefined {
  return h.confirmedAt ?? h.cancelledAt ?? h.expiredAt ?? h.requestedAt;
}

export function handoffAgeMinutes(h: HandoffItem): number {
  if (!h.requestedAt) return 0;
  return Math.floor((Date.now() - new Date(h.requestedAt).getTime()) / 60000);
}

export function isHandoffOverdue(h: HandoffItem): boolean {
  return isHandoffOpen(h) && handoffAgeMinutes(h) >= HANDOFF_OVERDUE_MINUTES;
}

/**
 * Loads handoffs for the dispatch desk and keeps them live via the shared
 * WebSocket notification stream — no polling. Exposes the open subset, an
 * overdue count for the tab badge, and a cancel action.
 */
export function useHandoffs() {
  const [items, setItems] = useState<HandoffItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [cancellingId, setCancellingId] = useState<string | null>(null);

  const { notifications } = useNotificationsState();
  const lastEventTsRef = useRef(0);

  const refetch = useCallback(async () => {
    try {
      const res = await api.get<HandoffItem[]>('/admin/handoffs');
      setItems(Array.isArray(res.data) ? res.data : []);
    } catch {
      /* keep previous list on transient errors */
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void refetch(); }, [refetch]);

  // Real-time refresh: when a handoff-related event lands in the WS feed, refetch.
  useEffect(() => {
    const latest = notifications.find(n => HANDOFF_EVENTS.has(n.event));
    if (latest && latest.timestamp > lastEventTsRef.current) {
      lastEventTsRef.current = latest.timestamp;
      void refetch();
    }
  }, [notifications, refetch]);

  const cancel = useCallback(async (id: string, reason: string): Promise<boolean> => {
    setCancellingId(id);
    try {
      await api.post(`/admin/handoffs/${id}/cancel`, null, {
        params: reason.trim() ? { reason: reason.trim() } : {},
      });
      await refetch();
      return true;
    } catch {
      return false;
    } finally {
      setCancellingId(null);
    }
  }, [refetch]);

  const open = useMemo(
    () => items
      .filter(isHandoffOpen)
      .sort((a, b) => {
        // Overdue first, then oldest-requested first (most urgent on top).
        const ov = Number(isHandoffOverdue(b)) - Number(isHandoffOverdue(a));
        if (ov !== 0) return ov;
        return new Date(a.requestedAt ?? 0).getTime() - new Date(b.requestedAt ?? 0).getTime();
      }),
    [items],
  );

  const overdueCount = useMemo(() => open.filter(isHandoffOverdue).length, [open]);

  // Completed transfers, newest-ended first — the retained lifecycle history.
  const history = useMemo(
    () => items
      .filter(isHandoffTerminal)
      .sort((a, b) => new Date(handoffEndedAt(b) ?? 0).getTime() - new Date(handoffEndedAt(a) ?? 0).getTime()),
    [items],
  );

  return { items, open, history, overdueCount, loading, cancel, cancellingId, refetch };
}
