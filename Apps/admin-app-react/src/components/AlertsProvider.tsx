import {
  createContext, useContext, useEffect, useState, useCallback, useMemo, useRef, ReactNode,
} from 'react';
import { useNavigate as useRouter } from 'react-router-dom';

import { toast } from '@/lib/toast';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useLocaleStore } from '@/lib/i18n';
import { FR_COPY } from '@/lib/ux-copy';
import { EN_COPY } from '@/lib/en-copy';
import { AR_COPY } from '@/lib/ar-copy';
import { safeStorage } from '@/lib/storage';

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
  
  'route.validated': { category: 'route', severity: 'info', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes' },
  'route.schedule_changed': { category: 'route', severity: 'warning', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes' },
  'route.stop_added': { category: 'route', severity: 'info', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes' },
  'route.stop_removed': { category: 'route', severity: 'warning', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes' },
  'delivery.handoff_confirmed': { category: 'route', severity: 'info', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes' },
  'handoff.requested': { category: 'route', severity: 'warning', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'handoff.overdue':   { category: 'route', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'handoff.cancelled': { category: 'route', severity: 'warning', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },
  'sla.breach': { category: 'delivery', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/dispatch-desk' },

  // Transfer events
  'STOPS_TRANSFERRED_OUT': { category: 'route', severity: 'warning', navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes' },
  'STOPS_TRANSFERRED_IN':  { category: 'route', severity: 'info',    navigateTo: p => p.routeId ? `/routes/${p.routeId}` : '/routes' },
  'erp.sync_failed':       { category: 'delivery', severity: 'critical', navigateTo: p => p.deliveryId ? `/deliveries/${p.deliveryId}` : '/deliveries' },
  'erp.orders_ready':      { category: 'erp',      severity: 'info',     navigateTo: () => '/import' },
};

// ── localStorage ──────────────────────────────────────────────────────────────

const LS_KEY = 'admin_notifications';
const MAX_STORED = 50;

function loadFromStorage(): Notification[] {
  try {
    const raw = typeof window !== 'undefined' ? safeStorage.getItem(LS_KEY) : null;
    return raw ? (JSON.parse(raw) as Notification[]) : [];
  } catch { return []; }
}

function saveToStorage(n: Notification[]) {
  try { safeStorage.setItem(LS_KEY, JSON.stringify(n.slice(0, MAX_STORED))); } catch {}
}

// ── Context ───────────────────────────────────────────────────────────────────

interface NotificationsStateContextType {
  notifications: Notification[];
  unreadCount: number;
}
interface NotificationsActionsContextType {
  markRead: (id: string) => void;
  markAllRead: () => void;
  clearAll: () => void;
}

const NotificationsStateContext = createContext<NotificationsStateContextType>({
  notifications: [],
  unreadCount: 0,
});

const NotificationsActionsContext = createContext<NotificationsActionsContextType>({
  markRead: () => {},
  markAllRead: () => {},
  clearAll: () => {},
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

export default function NotificationsProvider({ children }: { children: ReactNode }) {
  const [notifs, setNotifs] = useState<Notification[]>([]);
  const router = useRouter();
  const stompRef = useRef<Client | null>(null);

  useEffect(() => { setNotifs(loadFromStorage()); }, []);

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
      motif: raw.motif ?? raw.reason ?? '',
      elapsed: String(raw.slaParams?.elapsed ?? ''),
      limit: String(raw.slaParams?.limit ?? ''),
      etaAt: raw.etaAt ?? '',
      stopCount: String(raw.stopCount ?? ''),
      routeDurationMinutes: String(raw.routeDurationMinutes ?? ''),
      routeDistanceKm: String(raw.routeDistanceKm ?? ''),
      slaMessage: raw.slaMessage ?? '',
      count: String(raw.count ?? ''),
      // Rich fields the backend already serializes — used for human-readable copy.
      dropoffAddress: raw.dropoffAddress ?? '',
      totalAmount: raw.totalAmount != null ? String(raw.totalAmount) : '',
      currency: raw.currency ?? '',
      isCod: raw.isCod ? 'true' : '',
      plannedStartTime: typeof raw.plannedStartTime === 'string' ? raw.plannedStartTime : '',
      plannedEndTime: typeof raw.plannedEndTime === 'string' ? raw.plannedEndTime : '',
      transitSlaMinutes: String(raw.transitSlaMinutes ?? ''),
    };

    const ts = Date.now();
    const activeLocale = useLocaleStore.getState().locale || 'fr';
    const copyDict = activeLocale === 'ar' ? AR_COPY : (activeLocale === 'en' ? EN_COPY : FR_COPY);
    const eventCopy = (copyDict.notifications as any)[event];

    const title = eventCopy?.title || event;
    const message = typeof eventCopy?.message === 'function' ? eventCopy.message(p) : '';

    const notif: Notification = {
      id: `${event}-${p.deliveryId || p.routeId || ts}-${ts}`,
      event,
      category: cfg.category,
      severity: cfg.severity,
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

    setNotifs(prev => {
      const next = [notif, ...prev].slice(0, MAX_STORED);
      saveToStorage(next);
      return next;
    });

    const localized = getLocalizedNotif(notif, activeLocale);

    const dest = cfg.navigateTo(p);
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

  // WebSocket
  useEffect(() => {
    const wsBase = import.meta.env.VITE_WS_BASE_URL
      ?? import.meta.env.VITE_API_BASE_URL
      ?? (typeof window !== 'undefined' ? `${window.location.protocol}//${window.location.host}` : '');
    const wsUrl = `${wsBase}/ws`;

    if (import.meta.env.DEV) {
      console.log('[Notifications] Connecting to WebSocket at:', wsUrl);
    }

    let retryCount = 0;

    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: (frame) => {
        retryCount = 0;
        if (import.meta.env.DEV) {
          console.log('[Notifications] Connected to STOMP');
        }

        const deliveriesTopic = '/topic/admin.deliveries';
        const routesTopic     = '/topic/admin.routes';
        const erpTopic        = '/topic/admin.erp';

        client.subscribe(deliveriesTopic, msg => {
          if (import.meta.env.DEV) {
            console.log('[Notifications] Received message on deliveriesTopic:', msg.body);
          }
          try { 
            const cloudEvent = JSON.parse(msg.body);
            if (import.meta.env.DEV) {
              console.log('[Notifications] Parsed deliveriesTopic JSON:', cloudEvent);
            }
            const payload = cloudEvent.data || cloudEvent;
            payload.event = cloudEvent.type || payload.event;
            if (import.meta.env.DEV) {
              console.log('[Notifications] Final payload for addNotification:', payload);
            }
            addNotification(payload); 
          } catch(e) { console.error('[Notifications] deliveriesTopic Parse error:', e); }
        });
        client.subscribe(routesTopic, msg => {
          if (import.meta.env.DEV) {
            console.log('[Notifications] Received message on routesTopic:', msg.body);
          }
          try { 
            const cloudEvent = JSON.parse(msg.body);
            if (import.meta.env.DEV) {
              console.log('[Notifications] Parsed routesTopic JSON:', cloudEvent);
            }
            const payload = cloudEvent.data || cloudEvent;
            payload.event = cloudEvent.type || payload.event;
            if (import.meta.env.DEV) {
              console.log('[Notifications] Final payload for addNotification:', payload);
            }
            addNotification(payload); 
          } catch(e) { console.error('[Notifications] routesTopic Parse error:', e); }
        });
        client.subscribe(erpTopic, msg => {
          if (import.meta.env.DEV) {
            console.log('[Notifications] Received message on erpTopic:', msg.body);
          }
          try { 
            const cloudEvent = JSON.parse(msg.body);
            if (import.meta.env.DEV) {
              console.log('[Notifications] Parsed erpTopic JSON:', cloudEvent);
            }
            const payload = cloudEvent.data || cloudEvent;
            payload.event = cloudEvent.type || payload.event;
            if (import.meta.env.DEV) {
              console.log('[Notifications] Final payload for addNotification:', payload);
            }
            addNotification(payload); 
          } catch(e) { console.error('[Notifications] erpTopic Parse error:', e); }
        });
      },
      onStompError: frame => {
        if (retryCount === 0 || retryCount % 5 === 0) {
          console.warn('[Notifications] STOMP unavailable (attempt', retryCount + 1, '):', frame.headers?.message);
        }
        retryCount++;
        client.reconnectDelay = Math.min(5000 * Math.pow(2, retryCount - 1), 60_000);
      },
      onWebSocketClose: () => {
        if (retryCount <= 1) console.warn('[Notifications] WebSocket closed');
      }
    });

    client.activate();
    stompRef.current = client;
    return () => { client.deactivate(); };
  }, [addNotification]);

  const markRead = useCallback((id: string) => {
    setNotifs(prev => { const n = prev.map(x => x.id === id ? { ...x, read: true } : x); saveToStorage(n); return n; });
  }, []);

  const markAllRead = useCallback(() => {
    setNotifs(prev => { const n = prev.map(x => ({ ...x, read: true })); saveToStorage(n); return n; });
  }, []);

  const clearAll = useCallback(() => { setNotifs([]); saveToStorage([]); }, []);

  const unreadCount = notifs.filter(n => !n.read).length;

  const actionsValue = useMemo(() => ({
    markRead,
    markAllRead,
    clearAll,
  }), [markRead, markAllRead, clearAll]);

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

  const cfg = (copyDict.notifications as any)[n.event];
  if (!cfg) {
    return { title: n.title, message: n.message };
  }
  const title = cfg.title || n.title;
  const p = {
    ...(n.eventParams || {}),
    driverName: n.eventParams?.driverName ?? '',
    routeName:  n.eventParams?.routeName  ?? n.routeName ?? '',
  };
  const message = typeof cfg.message === 'function' ? cfg.message(p) : n.message;
  return { title, message };
}

export { NotificationsProvider as AlertsProvider };
