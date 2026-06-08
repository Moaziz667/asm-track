import {
  createContext, useCallback, useContext, useEffect, useRef, useState, ReactNode,
} from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { safeStorage } from '@/lib/storage';

// ── Single multiplexed realtime connection ─────────────────────────────────────
// One STOMP-over-SockJS client for the whole authenticated app. Consumers (the
// notifications feed, the dashboard, the activity ticker) attach via
// useRealtimeEvent instead of each opening their own socket. Events nudge; the
// REST/React-Query cache stays the source of truth.

export type RealtimeCategory = 'delivery' | 'route' | 'erp';

export interface RealtimeEvent {
  /** CloudEvent type, e.g. 'delivery.completed'. */
  type: string;
  category: RealtimeCategory;
  /** cloudEvent.data, with `.event` set to `type` for legacy consumers. */
  payload: Record<string, any>;
  receivedAt: number;
}

/** '*' matches every event; otherwise an explicit list of CloudEvent types. */
export type RealtimeEventTypes = '*' | readonly string[];
type Handler = (evt: RealtimeEvent) => void;
interface Subscriber { types: RealtimeEventTypes; handler: Handler; }

interface RealtimeContextValue {
  connected: boolean;
  subscribe: (types: RealtimeEventTypes, handler: Handler) => () => void;
}

const RealtimeContext = createContext<RealtimeContextValue>({
  connected: false,
  subscribe: () => () => {},
});

const TOPICS: { topic: string; category: RealtimeCategory }[] = [
  { topic: '/topic/admin.deliveries', category: 'delivery' },
  { topic: '/topic/admin.routes', category: 'route' },
  { topic: '/topic/admin.erp', category: 'erp' },
];

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const subscribersRef = useRef<Set<Subscriber>>(new Set());
  const [connected, setConnected] = useState(false);

  const subscribe = useCallback((types: RealtimeEventTypes, handler: Handler) => {
    const sub: Subscriber = { types, handler };
    subscribersRef.current.add(sub);
    return () => { subscribersRef.current.delete(sub); };
  }, []);

  const dispatch = useCallback((evt: RealtimeEvent) => {
    subscribersRef.current.forEach(sub => {
      if (sub.types === '*' || sub.types.includes(evt.type)) {
        try { sub.handler(evt); }
        catch (e) { console.error('[Realtime] subscriber handler threw:', e); }
      }
    });
  }, []);

  useEffect(() => {
    const wsBase = import.meta.env.VITE_WS_BASE_URL
      ?? import.meta.env.VITE_API_BASE_URL
      ?? (typeof window !== 'undefined' ? `${window.location.protocol}//${window.location.host}` : '');
    const wsUrl = `${wsBase}/ws`;
    let retryCount = 0;

    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      // Re-read the JWT on every (re)connect so the STOMP CONNECT frame carries a
      // fresh token after a silent renew — the backend interceptor rejects frames
      // without a valid Authorization header.
      beforeConnect: () => {
        const token = safeStorage.getItem('access_token');
        client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },
      onConnect: () => {
        retryCount = 0;
        setConnected(true);
        TOPICS.forEach(({ topic, category }) => {
          client.subscribe(topic, msg => {
            try {
              const cloudEvent = JSON.parse(msg.body);
              const payload = cloudEvent.data || cloudEvent;
              const type = cloudEvent.type || payload.event;
              payload.event = type;
              dispatch({ type, category, payload, receivedAt: Date.now() });
            } catch (e) {
              console.error(`[Realtime] parse error on ${topic}:`, e);
            }
          });
        });
      },
      onStompError: frame => {
        if (retryCount === 0 || retryCount % 5 === 0)
          console.warn('[Realtime] STOMP unavailable (attempt', retryCount + 1, '):', frame.headers?.message);
        retryCount++;
        client.reconnectDelay = Math.min(5000 * Math.pow(2, retryCount - 1), 60_000);
      },
      onWebSocketClose: () => { setConnected(false); },
    });

    client.activate();
    return () => { void client.deactivate(); };
  }, [dispatch]);

  return (
    <RealtimeContext.Provider value={{ connected, subscribe }}>
      {children}
    </RealtimeContext.Provider>
  );
}

/**
 * Subscribe to realtime events for the lifetime of the calling component.
 * The handler may change every render without resubscribing — only a change in
 * the type set re-attaches. Safe to call when no RealtimeProvider is mounted
 * (auth pages): it simply never fires.
 */
export function useRealtimeEvent(types: RealtimeEventTypes, handler: Handler) {
  const { subscribe } = useContext(RealtimeContext);
  const handlerRef = useRef(handler);
  handlerRef.current = handler;
  const typesKey = types === '*' ? '*' : [...types].join(',');
  useEffect(() => {
    const unsub = subscribe(types, evt => handlerRef.current(evt));
    return unsub;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subscribe, typesKey]);
}

/** True while the realtime socket is connected. */
export function useRealtimeStatus() {
  return useContext(RealtimeContext).connected;
}
