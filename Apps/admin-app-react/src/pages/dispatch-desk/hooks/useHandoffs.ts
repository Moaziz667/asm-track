import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { useNotificationsState } from '@/components/AlertsProvider';
import { useOpsSettings, HANDOFF_PENDING_KEY, HANDOFF_PENDING_DEFAULT, HANDOFF_AUTO_CANCEL_KEY, HANDOFF_AUTO_CANCEL_DEFAULT } from '@/hooks/useOpsSettings';
import type { HandoffItem } from '../types';

/** Fallback only — the live value is the tenant's `ops.handoff.pending-minutes` setting. */
export const HANDOFF_OVERDUE_MINUTES = HANDOFF_PENDING_DEFAULT;

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

export function isHandoffOverdue(h: HandoffItem, pendingMinutes: number = HANDOFF_OVERDUE_MINUTES): boolean {
  return isHandoffOpen(h) && handoffAgeMinutes(h) >= pendingMinutes;
}

/**
 * Dev-only fixture, served instead of the API when the page is opened with
 * `?mockHandoffs=1`. The tab renders nothing at all when the list is empty, so
 * without an in-field parcel actually changing hands there is no way to look at
 * the layout. Ages are relative to load so the SLA countdown really runs: one
 * comfortably inside the window, one nearly out of time, one past it.
 */
const minutesAgo = (m: number) => new Date(Date.now() - m * 60000).toISOString();

const MOCK_HANDOFFS: HandoffItem[] = [
  {
    id: 'mock-1', state: 'IN_PROGRESS',
    deliveryId: '11111111-1111-1111-1111-111111111111', erpOrderId: 'TUN/OUT/00412',
    clientName: 'Café El Manar Ennasr', dropoffAddress: 'Rue Ibn Khaldoun, Ennasr 2',
    fromDriverName: 'Mehdi Sassi', toDriverName: 'Yassine Trabelsi',
    requestedAt: minutesAgo(6), inProgressAt: minutesAgo(4),
    tokenExpiresAt: new Date(Date.now() + 3 * 60000).toISOString(),
    requestedBy: 'Aziz (dispatch)', reason: 'Véhicule en panne',
  },
  {
    id: 'mock-2', state: 'REQUESTED',
    deliveryId: '22222222-2222-2222-2222-222222222222', erpOrderId: 'TUN/OUT/00413',
    clientName: 'Superette Lac 2', dropoffAddress: 'Rue du Lac Turkana, Tunis',
    fromDriverName: 'Sami Gharbi', toDriverName: 'Mehdi Sassi',
    requestedAt: minutesAgo(43), requestedBy: 'Aziz (dispatch)', reason: 'Tournée en retard',
  },
  {
    id: 'mock-3', state: 'REQUESTED',
    deliveryId: '33333333-3333-3333-3333-333333333333', erpOrderId: 'TUN/OUT/00414',
    clientName: 'Pharmacie Ennasr', dropoffAddress: 'Avenue Hédi Nouira',
    fromDriverName: 'Yassine Trabelsi', toDriverName: 'Sami Gharbi',
    requestedAt: minutesAgo(72), requestedBy: 'Aziz (dispatch)', reason: 'Fin de service',
  },
  {
    id: 'mock-4', state: 'CONFIRMED',
    deliveryId: '44444444-4444-4444-4444-444444444444', erpOrderId: 'TUN/OUT/00398',
    clientName: 'Boulangerie Menzah 9', fromDriverName: 'Mehdi Sassi', toDriverName: 'Sami Gharbi',
    requestedAt: minutesAgo(180), inProgressAt: minutesAgo(176), confirmedAt: minutesAgo(171),
    requestedBy: 'Aziz (dispatch)', reason: 'Zone plus proche',
  },
  {
    id: 'mock-5', state: 'EXPIRED',
    deliveryId: '55555555-5555-5555-5555-555555555555', erpOrderId: 'TUN/OUT/00399',
    clientName: 'Restaurant Le Golfe', fromDriverName: 'Sami Gharbi', toDriverName: 'Yassine Trabelsi',
    requestedAt: minutesAgo(300), inProgressAt: minutesAgo(295), expiredAt: minutesAgo(240),
    requestedBy: 'Aziz (dispatch)', reason: 'Colis trop volumineux',
  },
  {
    id: 'mock-6', state: 'CANCELLED',
    deliveryId: '66666666-6666-6666-6666-666666666666', erpOrderId: 'TUN/OUT/00400',
    clientName: 'Épicerie Bardo', fromDriverName: 'Mehdi Sassi', toDriverName: 'Yassine Trabelsi',
    requestedAt: minutesAgo(420), cancelledAt: minutesAgo(400),
    requestedBy: 'Aziz (dispatch)', cancelledBy: 'Aziz (dispatch)', reason: 'Client injoignable',
  },
];

const mockHandoffsEnabled = () =>
  import.meta.env.DEV && new URLSearchParams(window.location.search).get('mockHandoffs') === '1';

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

  // Tenant thresholds, so the desk ages a handoff by the same clock the backend sweeps it with.
  const { getInt } = useOpsSettings();
  const pendingMinutes = getInt(HANDOFF_PENDING_KEY, HANDOFF_PENDING_DEFAULT);
  const autoCancelMinutes = getInt(HANDOFF_AUTO_CANCEL_KEY, HANDOFF_AUTO_CANCEL_DEFAULT);

  const refetch = useCallback(async () => {
    if (mockHandoffsEnabled()) {
      setItems(MOCK_HANDOFFS);
      setLoading(false);
      return;
    }
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
    if (mockHandoffsEnabled()) {
      setItems(prev => prev.map(h => h.id === id
        ? { ...h, state: 'CANCELLED', cancelledAt: new Date().toISOString(), cancelledBy: 'Aziz (dispatch)', reason: reason.trim() || h.reason }
        : h));
      return true;
    }
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
        const ov = Number(isHandoffOverdue(b, pendingMinutes)) - Number(isHandoffOverdue(a, pendingMinutes));
        if (ov !== 0) return ov;
        return new Date(a.requestedAt ?? 0).getTime() - new Date(b.requestedAt ?? 0).getTime();
      }),
    [items, pendingMinutes],
  );

  const overdueCount = useMemo(
    () => open.filter(h => isHandoffOverdue(h, pendingMinutes)).length,
    [open, pendingMinutes],
  );

  // Completed transfers, newest-ended first — the retained lifecycle history.
  const history = useMemo(
    () => items
      .filter(isHandoffTerminal)
      .sort((a, b) => new Date(handoffEndedAt(b) ?? 0).getTime() - new Date(handoffEndedAt(a) ?? 0).getTime()),
    [items],
  );

  return { items, open, history, overdueCount, loading, cancel, cancellingId, refetch,
           pendingMinutes, autoCancelMinutes };
}
