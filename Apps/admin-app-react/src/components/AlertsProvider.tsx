import {
  createContext, useContext, useEffect, useState, useCallback, useMemo, ReactNode,
} from 'react';
import { useNavigate as useRouter } from 'react-router-dom';

import { toast } from '@/lib/toast';
import { useRealtimeEvent } from '@/components/RealtimeProvider';
import { useLocaleStore } from '@/lib/i18n';
import { FR_COPY } from '@/lib/ux-copy';
import { EN_COPY } from '@/lib/en-copy';
import { AR_COPY } from '@/lib/ar-copy';
import { dget, tlabel } from '@/lib/i18n-dict';
import { getApiError } from '@/lib/errors';

/** Shape of a per-event notification copy entry (title/message may be templated). */
type NotifCopyEntry = {
  title?: string | ((p: Record<string, unknown>) => string);
  message?: (p: Record<string, unknown>) => string;
};
import { api } from '@/lib/api';
import { notifDestination } from '@/lib/dispatch-link';

// ── Types ─────────────────────────────────────────────────────────────────────

export interface Notification {
  id: string;
  event: string;
  category: 'delivery' | 'route' | 'erp';
  severity: 'critical' | 'warning' | 'info';
  count?: number;
  title: string;
  message: string;
  deliveryId?: string;
  routeId?: string;
  driverId?: string;
  driverName?: string;
  orderId?: string;
  clientName?: string;
  routeName?: string;
  timestamp: number;
  read: boolean;
  eventParams?: Record<string, string>;
}

interface NotificationsContextType {
  notifications: Notification[];
  unreadCount: number;
  markRead: (id: string) => void;
  markAllRead: () => void;
  clearAll: () => void;
  acknowledge: (id: string) => void;
}

// ── Event config ──────────────────────────────────────────────────────────────

type EventConfig = {
  category: Notification['category'];
  severity: Notification['severity'];
  navigateTo: (p: Record<string, string>) => string;
};

const EVENT_MAP: Record<string, EventConfig> = {
  // Legacy support
  FAILED: { category: 'delivery', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  DELIVERED: { category: 'delivery', severity: 'info', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  
  // New Event Names
  'delivery.created': { category: 'delivery', severity: 'info', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'delivery.scheduled': { category: 'delivery', severity: 'info', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'delivery.picked_up': { category: 'delivery', severity: 'info', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'delivery.in_transit': { category: 'delivery', severity: 'info', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'delivery.completed': { category: 'delivery', severity: 'info', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'delivery.failed': { category: 'delivery', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'delivery.cancelled': { category: 'delivery', severity: 'warning', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'delivery.reassigned': { category: 'delivery', severity: 'info', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'delivery.reassigned_away': { category: 'delivery', severity: 'warning', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'delivery.handoff_required': { category: 'delivery', severity: 'warning', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'delivery.replanned': { category: 'delivery', severity: 'warning', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  
  'route.validated': { category: 'route', severity: 'info', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'route.schedule_changed': { category: 'route', severity: 'warning', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'route.stop_added': { category: 'route', severity: 'info', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'route.stop_removed': { category: 'route', severity: 'warning', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'delivery.handoff_confirmed': { category: 'route', severity: 'info', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'handoff.requested': { category: 'route', severity: 'warning', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'handoff.overdue':   { category: 'route', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'handoff.cancelled': { category: 'route', severity: 'warning', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'sla.breach': { category: 'delivery', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  // Unified SLA event (one per health transition) from the new SlaStateService.
  'sla.alert': { category: 'delivery', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'pickup.overdue': { category: 'route', severity: 'critical', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/dispatch-desk' },
  'delivery.backorder_created': { category: 'delivery', severity: 'info', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },

  // Transfer events
  'STOPS_TRANSFERRED_OUT': { category: 'route', severity: 'warning', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'STOPS_TRANSFERRED_IN':  { category: 'route', severity: 'info',    navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'ROUTE_STARTED':         { category: 'route', severity: 'info',    navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'PICKUP_CONFIRMED':      { category: 'route', severity: 'info',    navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes-table' },
  'erp.sync_failed':       { category: 'delivery', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'erp.orders_ready':      { category: 'erp',      severity: 'info',     navigateTo: () => '/import?tab=ready' },
};

// The server (/api/admin/notifications) is the source of truth — read-state is
// shared across admins and survives refresh/offline. We keep only an in-memory
// cap so the live WS feed can't grow unbounded between refetches.
const MAX_STORED = 200;

// ── Context ───────────────────────────────────────────────────────────────────

interface NotificationsStateContextType {
  notifications: Notification[];
  unreadCount: number;
}
interface NotificationsActionsContextType {
  markRead: (id: string) => void;
  markAllRead: () => void;
  clearAll: () => void;
  acknowledge: (id: string) => void;
  refresh: () => Promise<void>;
}

const NotificationsStateContext = createContext<NotificationsStateContextType>({
  notifications: [],
  unreadCount: 0,
});

const NotificationsActionsContext = createContext<NotificationsActionsContextType>({
  markRead: () => {},
  markAllRead: () => {},
  clearAll: () => {},
  acknowledge: () => {},
  refresh: async () => {},
});

export function useNotificationsState() {
  return useContext(NotificationsStateContext);
}

export function useNotificationsActions() {
  return useContext(NotificationsActionsContext);
}

export function useNotifications() {
  const state = useContext(NotificationsStateContext);
  const actions = useContext(NotificationsActionsContext);
  return {
    ...state,
    ...actions,
  };
}

export function useAlerts() {
  return useNotifications();
} // backward compat

// ── Provider ──────────────────────────────────────────────────────────────────

const mapResponseToNotification = (item: any): Notification => {
  const cfg = EVENT_MAP[item.event];
  return {
    id: item.id,
    event: item.event,
    category: cfg ? cfg.category : 'delivery',
    severity: (item.severity ? String(item.severity).toLowerCase() : (cfg ? cfg.severity : 'info')) as 'critical' | 'warning' | 'info',
    title: item.title,
    message: item.message,
    deliveryId: item.deliveryId || undefined,
    routeId: item.routeId || undefined,
    driverId: item.driverId || undefined,
    driverName: item.driverName || undefined,
    orderId: item.orderId || undefined,
    clientName: item.clientName || undefined,
    routeName: item.routeName || undefined,
    timestamp: item.timestamp,
    read: item.read,
    eventParams: item.eventParams || {},
  };
};

export default function NotificationsProvider({ children }: { children: ReactNode }) {
  const [notifs, setNotifs] = useState<Notification[]>([]);
  const router = useRouter();

  const refresh = useCallback(async () => {
    try {
      const res = await api.get('/api/admin/notifications?size=100');
      if (res.data && Array.isArray(res.data.content)) {
        setNotifs(res.data.content.map(mapResponseToNotification));
      }
    } catch (err) {
      // The persistent-history endpoint may not be deployed yet; degrade to the
      // live (in-session) feed instead of throwing an uncaught rejection.
      if (getApiError(err).status === 404) {
        console.warn('[Notifications] History endpoint unavailable (404) — live feed only.');
        return;
      }
      console.error('[Notifications] Failed to fetch notification history from server:', err);
      throw err;
    }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const addNotification = useCallback((raw: Record<string, any>) => {
    if (import.meta.env.DEV) {
      console.log('[Notifications] addNotification called with raw payload:', raw);
    }
    const event = raw.event || raw.status || '';
    if (import.meta.env.DEV) {
      console.log('[Notifications] Resolved event name:', event);
    }
    const cfg = EVENT_MAP[event];
    if (!cfg) {
      if (import.meta.env.DEV) {
        console.warn('[Notifications] Unknown event ignored (not mapped):', event, raw);
      }
      return;
    }
    if (import.meta.env.DEV) {
      console.log('[Notifications] Found mapped config:', cfg);
    }

    const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const cleanRef = (v: unknown): string => {
      const s = String(v ?? '');
      return s && !UUID_RE.test(s) ? s : '';
    };

    const driverIdRaw = raw.driverId ?? raw.newDriverId ?? raw.previousDriverId ?? '';

    const p: Record<string, string> = {
      clientName: raw.clientName ?? '',
      clientPhone: raw.clientPhone ?? '',
      routeName: raw.routeName ?? '',
      deliveryId: raw.deliveryId ?? '',
      routeId: raw.routeId ?? '',
      driverId: String(driverIdRaw ?? ''),
      orderId: cleanRef(raw.erpOrderId ?? raw.orderId),
      erpOrderId: cleanRef(raw.erpOrderId ?? raw.orderId),
      driverName: raw.driverName ?? '',
      fromDriverName: raw.fromDriverName ?? '',
      toDriverName: raw.toDriverName ?? '',
      reason: raw.reason ?? '',
      blNumber: raw.blNumber ?? '',
      motif: raw.motif ?? raw.reason ?? '',
      elapsed: String(raw.slaParams?.elapsed ?? ''),
      limit: String(raw.slaParams?.limit ?? ''),
      etaAt: raw.etaAt ?? '',
      stopCount: String(raw.stopCount ?? ''),
      depotName: raw.depotName ?? '',
      parcelCount: String(raw.parcelCount ?? ''),
      routeDurationMinutes: String(raw.routeDurationMinutes ?? ''),
      routeDistanceKm: String(raw.routeDistanceKm ?? ''),
      slaMessage: raw.slaMessage ?? '',
      count: String(raw.count ?? ''),
      // Rich fields the backend already serializes — used for human-readable copy.
      dropoffAddress: raw.dropoffAddress ?? '',
      totalAmount: raw.totalAmount != null ? String(raw.totalAmount) : '',
      currency: raw.currency ?? '',
      plannedStartTime: typeof raw.plannedStartTime === 'string' ? raw.plannedStartTime : '',
      plannedEndTime: typeof raw.plannedEndTime === 'string' ? raw.plannedEndTime : '',
      transitSlaMinutes: String(raw.transitSlaMinutes ?? ''),
    };

    // The live WS payload nests SLA fields under `slaParams` (phase/health/reasonKey/dueAt +
    // reasonParams); the persisted record stores them flat. Flatten here so the live item renders
    // identically to the refreshed one (otherwise sla.alert shows up empty until a manual refresh).
    if (raw.slaParams && typeof raw.slaParams === 'object') {
      for (const [k, v] of Object.entries(raw.slaParams as Record<string, unknown>)) {
        if (p[k] === undefined || p[k] === '') p[k] = v == null ? '' : String(v);
      }
    }

    const ts = Date.now();
    const activeLocale = useLocaleStore.getState().locale || 'fr';
    const copyDict = activeLocale === 'ar' ? AR_COPY : (activeLocale === 'en' ? EN_COPY : FR_COPY);
    const eventCopy = dget<NotifCopyEntry>(copyDict.notifications, event);

    const title = (typeof eventCopy?.title === 'string' ? eventCopy.title : undefined) || event;
    const message = typeof eventCopy?.message === 'function' ? eventCopy.message(p) : '';

    const notif: Notification = {
      id: `${event}-${p.deliveryId || p.routeId || ts}-${ts}`,
      event,
      category: cfg.category,
      severity: (raw.severity ? String(raw.severity).toLowerCase() : cfg.severity) as 'critical' | 'warning' | 'info',
      title,
      message,
      deliveryId: p.deliveryId || undefined,
      routeId: p.routeId || undefined,
      driverId: p.driverId || undefined,
      driverName: p.driverName || undefined,
      orderId: p.orderId || undefined,
      clientName: p.clientName || undefined,
      routeName: p.routeName || undefined,
      timestamp: ts,
      read: false,
      eventParams: p,
    };

    setNotifs(prev => [notif, ...prev].slice(0, MAX_STORED));

    const localized = getLocalizedNotif(notif, activeLocale);

    const dest = notifDestination(notif);
    const toastFn = cfg.severity === 'critical' ? toast.error
      : cfg.severity === 'warning' ? toast.warning
      : toast.info;

    const actionLabel = activeLocale === 'ar' ? 'عرض' : (activeLocale === 'en' ? 'View' : 'Voir');

    toastFn(localized.message, {
      description: localized.title,
      duration: cfg.severity === 'critical' ? Infinity : 5000,
      action: dest ? { label: actionLabel, onClick: () => router(dest) } : undefined,
    });
  }, [router]);

  // Live feed: consume the shared RealtimeProvider socket instead of opening our
  // own. Unknown events are filtered out inside addNotification (EVENT_MAP).
  useRealtimeEvent('*', evt => addNotification(evt.payload));

  const markRead = useCallback((id: string) => {
    setNotifs(prev => prev.map(x => x.id === id ? { ...x, read: true } : x));
    const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (UUID_RE.test(id)) {
      api.post(`/api/admin/notifications/${id}/read`).catch(err => {
        console.error(`[Notifications] Failed to mark notification ${id} as read on server:`, err);
      });
    }
  }, []);

  const markAllRead = useCallback(() => {
    setNotifs(prev => prev.map(x => ({ ...x, read: true })));
    api.post('/api/admin/notifications/read-all').catch(err => {
      console.error('[Notifications] Failed to mark all notifications as read on server:', err);
    });
  }, []);

  const clearAll = useCallback(() => {
    setNotifs([]);
    api.post('/api/admin/notifications/read-all').catch(err => {
      console.error('[Notifications] Failed to clear/read all notifications on server:', err);
    });
  }, []);

  const acknowledge = useCallback((id: string) => {
    setNotifs(prev => prev.filter(x => x.id !== id));
    const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (UUID_RE.test(id)) {
      api.post(`/api/admin/notifications/${id}/acknowledge`).catch(err => {
        console.error(`[Notifications] Failed to acknowledge notification ${id} on server:`, err);
      });
    }
  }, []);

  const unreadCount = notifs.filter(n => !n.read).length;

  const actionsValue = useMemo(() => ({
    markRead,
    markAllRead,
    clearAll,
    acknowledge,
    refresh,
  }), [markRead, markAllRead, clearAll, acknowledge, refresh]);

  const stateValue = useMemo(() => ({
    notifications: notifs,
    unreadCount,
  }), [notifs, unreadCount]);

  return (
    <NotificationsStateContext.Provider value={stateValue}>
      <NotificationsActionsContext.Provider value={actionsValue}>
        {children}
      </NotificationsActionsContext.Provider>
    </NotificationsStateContext.Provider>
  );
}

export function getLocalizedNotif(n: Notification, locale: string) {
  const copyDict = locale === 'ar'
    ? AR_COPY
    : (locale === 'en' ? EN_COPY : FR_COPY);

  // Unified SLA event: render "phase · health" + reason, enriched with client/order context.
  if (n.event === 'sla.alert') {
    const sl = copyDict.slaTimeline ?? {};
    const ep2 = (n.eventParams || {}) as Record<string, string>;
    // Live WS nests the SLA fields under slaParams; the persisted record stores them flat.
    const sp = ep2 as Record<string, string>;
    const phase = tlabel(sl.phase, sp.phase) ?? sp.phase ?? '';
    const health = tlabel(sl.health, sp.health) ?? sp.health ?? '';
    const reason = String(tlabel(sl.reason, sp.reasonKey) ?? '')
      .replace(/\{(\w+)\}/g, (_: string, k: string) => sp[k] ?? '');
    const ref = ep2.erpOrderId ?? ep2.orderId ?? sp.erpOrderId ?? n.orderId ?? '';
    const client = ep2.clientName ?? n.clientName ?? '';
    const driver = ep2.driverName ?? n.driverName ?? '';
    const context = [ref ? `#${ref}` : '', client, driver].filter(Boolean).join(' · ');
    const message = [reason, context].filter(Boolean).join(' — ') || n.message;
    return { title: `${phase}${health ? ' · ' + health : ''}`, message };
  }

  const cfg = dget<NotifCopyEntry>(copyDict.notifications, n.event);
  if (!cfg) {
    return { title: n.title, message: n.message };
  }
  const ep = (n.eventParams || {}) as Record<string, unknown>;
  const p = {
    ...ep,
    clientName: ep.clientName ?? n.clientName ?? '',
    driverName: ep.driverName ?? n.driverName ?? '',
    routeName:  ep.routeName  ?? n.routeName ?? '',
    deliveryId: ep.deliveryId ?? n.deliveryId ?? '',
    routeId:    ep.routeId ?? n.routeId ?? '',
    orderId:    ep.orderId ?? ep.erpOrderId ?? n.orderId ?? '',
    erpOrderId: ep.erpOrderId ?? ep.orderId ?? n.orderId ?? '',
    message: n.message,
  };
  const title = typeof cfg.title === 'function' ? cfg.title(p) : (cfg.title || n.title);
  const message = typeof cfg.message === 'function' ? cfg.message(p) : n.message;
  return { title, message };
}

export { NotificationsProvider as AlertsProvider };
