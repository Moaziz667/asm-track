
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { lazy as dynamic } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api } from '@/lib/api';
import { safeStorage } from '@/lib/storage';
import { apiWithToasts } from '@/lib/api-with-toasts';
import { formatMoney } from '@/lib/utils';
import { useT, getCopy } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { showSuccessToast, showErrorToast, showInfoToast } from '@/lib/toast-service';
import { Delivery, DeliveryItem, ProofOfDelivery, TimelineEvent } from '@/types';
import { format } from 'date-fns';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import {
  Tooltip,
  TooltipTrigger,
  TooltipContent,
} from '@/components/ui/tooltip';
import {
  IconAlertCircle,
  IconArrowLeft,
  IconBan,
  IconChevronDown,
  IconClock,
  IconFileText,
  IconMapPin,
  IconPencil,
  IconPhone,
  IconRefresh,
  IconX,
} from '@tabler/icons-react';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { SkeletonMap } from '@/components/feedback/SkeletonMap';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { colors, DRIVER_STATUS_COLOR } from '@/lib/design-tokens';
import RouteReportSection from '@/features/routes/RouteReportSection';
import { RouteHeader } from '@/components/route/RouteHeader';
import { RouteStats } from '@/components/route/RouteStats';
import stopTabsStyles from '@/styles/stop-tabs.module.scss';
import { BadgeStatusMap } from '@/components/route/StatusBadgeIcons';

// ─── Dynamic Map ──────────────────────────────────────────────────────────────
const RouteTrackingMap = dynamic(() => import('@/components/RouteTrackingMap'));

// ─── Types ────────────────────────────────────────────────────────────────────
type StopOrder = {
  id?: string;
  clientName?: string;
  clientPhone?: string;
  dropoffAddress?: string;
  totalAmount?: number;
  totalWeightKg?: number;
  totalQuantity?: number;
  priority?: string;
  source?: string;
  erpOrderId?: string;
  erpExternalRef?: string;
  deliveryInstructions?: string;
  currency?: string;
  isCod?: boolean;
  items?: DeliveryItem[];
};

type RouteStop = {
  id: string;
  deliveryId: string;
  stopOrder: number;
  status: string;
  arrivedAt?: string;
  completedAt?: string;
  notes?: string;
  deliveryAddress?: string;
  deliveryCity?: string;
  deliveryPostalCode?: string;
  dropoffLat?: number;
  dropoffLng?: number;
  dropoffPinned?: boolean;
  routeGeometry?: string;
  routeDistanceKm?: number;
  routeDurationMinutes?: number;
  routeEtaAt?: string;
  startTimeWindow?: string;
  endTimeWindow?: string;
  slaStatus?: string;
  delayMinutes?: number;
  delayStatus?: string;
  delayReason?: string;
  transitSlaMinutesComputed?: number;
  order?: StopOrder;
  delivery?: any;
  clientName?: string;
  codCollected?: boolean | null;
  codAmountCollected?: number | null;
  removedAt?: string;
  removedReason?: string;
};

type RouteDetail = {
  id: string;
  name: string;
  date: string;
  plannedStartTime?: string;
  plannedEndTime?: string;
  city?: string;
  status: 'DRAFT' | 'VALIDATED' | 'IN_PROGRESS' | 'CLOSED' | 'CANCELLED';
  createdAt?: string;
  createdBy?: string;
  validatedAt?: string;
  startedAt?: string;
  closedAt?: string;
  cumulativeDelayMinutes?: number;
  onTimeCompletionRate?: number;
  routeStartDelayMinutes?: number;
  totalStops?: number;
  completedStops?: number;
  failedStops?: number;
  partialStops?: number;
  pendingStops?: number;
  progressPercent?: number;
  totalDurationSeconds?: number;
  totalDistanceMeters?: number;
  isOptimized?: boolean;
  detectedZoneLabel?: string;
  routeGeometry?: string;
  departureTime?: string;
  stops: RouteStop[];
  driver?: { id: string; name?: string; phone?: string; available?: boolean; currentLat?: number; currentLng?: number };
  vehicle?: { id: string; name?: string; plate?: string; payloadKg?: number; type?: string };
  depot?: { id: string; name?: string; address?: string; city?: string; latitude?: number; longitude?: number };
  routeVersion?: number;
  legacyStops?: RouteStop[];
};

const st = colors.status;
const STOP_STATUS: Record<string, { color: string; bg: string; dot: string; border: string }> = {
  SCHEDULED:           { color: st.SCHEDULED.text,           bg: st.SCHEDULED.bg,           dot: st.SCHEDULED.dot,           border: st.SCHEDULED.border },
  PICKED_UP:           { color: st.PICKED_UP.text,           bg: st.PICKED_UP.bg,           dot: st.PICKED_UP.dot,           border: st.PICKED_UP.border },
  IN_TRANSIT:          { color: st.IN_TRANSIT.text,          bg: st.IN_TRANSIT.bg,          dot: st.IN_TRANSIT.dot,          border: st.IN_TRANSIT.border },
  DELIVERED:           { color: st.DELIVERED.text,           bg: st.DELIVERED.bg,           dot: st.DELIVERED.dot,           border: st.DELIVERED.border },
  COMPLETED:           { color: st.CLOSED.text,              bg: st.CLOSED.bg,              dot: st.CLOSED.dot,              border: st.CLOSED.border },
  FAILED:              { color: st.FAILED.text,              bg: st.FAILED.bg,              dot: st.FAILED.dot,              border: st.FAILED.border },
  PARTIAL:             { color: st.PARTIALLY_DELIVERED.text,  bg: st.PARTIALLY_DELIVERED.bg,  dot: st.PARTIALLY_DELIVERED.dot,  border: st.PARTIALLY_DELIVERED.border },
  PARTIALLY_DELIVERED: { color: st.PARTIALLY_DELIVERED.text,  bg: st.PARTIALLY_DELIVERED.bg,  dot: st.PARTIALLY_DELIVERED.dot,  border: st.PARTIALLY_DELIVERED.border },
  CANCELLED:           { color: st.CANCELLED.text,           bg: st.CANCELLED.bg,           dot: st.CANCELLED.dot,           border: st.CANCELLED.border },
  REMOVED:             { color: st.CANCELLED.text,           bg: st.CANCELLED.bg,           dot: st.CANCELLED.dot,           border: st.CANCELLED.border },
  REMOVED_REPLANNED:   { color: st.REMOVED_REPLANNED.text,   bg: st.REMOVED_REPLANNED.bg,   dot: st.REMOVED_REPLANNED.dot,   border: st.REMOVED_REPLANNED.border },
  REMOVED_CANCELLED:   { color: st.REMOVED_CANCELLED.text,   bg: st.REMOVED_CANCELLED.bg,   dot: st.REMOVED_CANCELLED.dot,   border: st.REMOVED_CANCELLED.border },
  FAILED_ATTEMPT:      { color: st.FAILED_ATTEMPT.text,      bg: st.FAILED_ATTEMPT.bg,      dot: st.FAILED_ATTEMPT.dot,      border: st.FAILED_ATTEMPT.border },
};

const DELAY_STYLE: Record<string, { color: string; bg: string; border: string }> = {
  EARLY:   { color: st.DELIVERED.text, bg: st.DELIVERED.bg, border: st.DELIVERED.border },
  ON_TIME: { color: st.DELIVERED.text, bg: st.DELIVERED.bg, border: st.DELIVERED.border },
  LATE:    { color: st.FAILED.text,    bg: st.FAILED.bg,    border: st.FAILED.border },
};

const STATUS_COLORS: Record<string, string> = {
  SCHEDULED: st.SCHEDULED.dot, PICKED_UP: st.PICKED_UP.dot, IN_TRANSIT: st.IN_TRANSIT.dot,
  COMPLETED: st.CLOSED.dot, DELIVERED: st.DELIVERED.dot, FAILED: st.FAILED.dot,
  CANCELLED: st.CANCELLED.dot, PARTIALLY_DELIVERED: st.PARTIALLY_DELIVERED.dot,
  UNSCHEDULED: st.UNSCHEDULED.dot, ARRIVED: st.IN_TRANSIT.dot,
};

const REMOVABLE_STOP_STATUSES = new Set(['PENDING', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT']);

// ─── Helpers ──────────────────────────────────────────────────────────────────
function fmt(v?: string | null) {
  if (!v) return '—';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : format(d, 'dd/MM HH:mm');
}
function fmtLong(v?: string | null) {
  if (!v) return '—';
  const d = new Date(v);
  return Number.isNaN(d.getTime()) ? v : format(d, 'dd MMM yyyy HH:mm');
}
function fmtMins(mins?: number | null): string {
  if (mins == null) return '0m';
  const a = Math.abs(Math.round(mins));
  const h = Math.floor(a / 60), m = a % 60;
  const s = mins < 0 ? '-' : '';
  return h > 0 ? `${s}${h}h ${m}m` : `${s}${m}m`;
}
function fmtDuration(sec?: number | null): string {
  if (!sec) return '—';
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}
function fmtDist(m?: number | null): string {
  if (!m) return '—';
  return m >= 1000 ? `${(m / 1000).toFixed(1)} km` : `${m} m`;
}
function fmtTimeWindow(s?: string | null) {
  if (!s) return '—';
  return String(s).slice(0, 5);
}
function normalizeTimeline(raw?: TimelineEvent[]): TimelineEvent[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .map((e) => ({ ...e, timestamp: e.timestamp ?? e.changedAt ?? '', actor: e.actor ?? e.changedBy }))
    .filter((e) => Boolean(e.timestamp))
    .sort((a, b) => +new Date(a.timestamp) - +new Date(b.timestamp));
}
function cleanNote(note?: string, copy?: any): string | undefined {
  if (!note) return undefined;
  const t = note.trim();
  if (!t) return undefined;
  const half = Math.floor(t.length / 2);
  const s = t.length % 2 === 0 && t.slice(0, half) === t.slice(half) ? t.slice(0, half) : t;
  if (s.startsWith('ADMIN_ACTION:')) {
    const r = s.match(/ - (.*?) \|/)?.[1]?.trim();
    const resolvedCopy = copy || getCopy(useLocaleStore.getState().locale || 'fr');
    let msg: string = resolvedCopy.routeDetailPage?.dispatchActionRecorded || 'Action de dispatch enregistrée.';
    if (s.includes('REPLAN')) msg = resolvedCopy.routeDetailPage?.deliveryReplanned || 'Livraison remise en file de planification.';
    else if (s.includes('REASSIGN')) msg = resolvedCopy.routeDetailPage?.deliveryReassigned || 'Livraison réaffectée à un autre chauffeur.';
    const reasonLabel = resolvedCopy.routeDetailPage?.reason || 'Motif';
    return r ? `${msg} ${reasonLabel}: ${r}` : msg;
  }
  return s;
}
function normalizePod(raw: any): ProofOfDelivery | null {
  if (!raw || typeof raw !== 'object') return null;
  return {
    signatureBase64: raw.signatureBase64, photoBase64: raw.photoBase64,
    signatureUrl: raw.signatureUrl, photoUrl: raw.photoUrl,
    comment: raw.comment,
    timestamp: raw.timestamp ?? raw.collectedAt, collectedAt: raw.collectedAt,
    latitude: raw.latitude ?? raw.lat, longitude: raw.longitude ?? raw.lng,
    lat: raw.lat, lng: raw.lng,
  };
}
function mediaSrc(url?: string, b64?: string): string | null {
  if (url) return url;
  if (b64) return b64.startsWith('data:') ? b64 : `data:image/png;base64,${b64}`;
  return null;
}

// ─── Main Component ───────────────────────────────────────────────────────────
export default function RouteDetailsPage() {
  const t = useT();
  const params = useParams<{ id: string }>();
  const routeId = Array.isArray(params?.id) ? params.id[0] : params?.id;
  const navigate = useNavigate();
  const stopRefs = useRef<Record<string, HTMLDivElement | null>>({});

  const [route, setRoute] = useState<RouteDetail | null>(null);
  const [driverOnlineStatus, setDriverOnlineStatus] = useState<string | null>(null);
  const [deliveryMap, setDeliveryMap] = useState<Record<string, Delivery>>({});
  const [timelineMap, setTimelineMap] = useState<Record<string, TimelineEvent[]>>({});
  const [podMap, setPodMap] = useState<Record<string, ProofOfDelivery | null>>({});
  const [loading, setLoading] = useState(true);
  const [expandedStops, setExpandedStops] = useState<Set<string>>(new Set());
  const [creatingBackorderFor, setCreatingBackorderFor] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<Record<string, 'details' | 'timeline' | 'pod'>>({});
  const [cancelStopTarget, setCancelStopTarget] = useState<{ stopId: string; client: string; isPickedUp: boolean } | null>(null);
  const [cancelStopReason, setCancelStopReason] = useState('');
  const [cancellingStop, setCancellingStop] = useState(false);
  const [removeStopTarget, setRemoveStopTarget] = useState<{ stopId: string; client: string } | null>(null);
  const [removeStopReason, setRemoveStopReason] = useState('');
  const [removingStop, setRemovingStop] = useState(false);
  const [editWindowTarget, setEditWindowTarget] = useState<{ stopId: string; client: string } | null>(null);
  const [editWindowStart, setEditWindowStart] = useState('');
  const [editWindowEnd, setEditWindowEnd] = useState('');
  const [editWindowReason, setEditWindowReason] = useState('');
  const [savingWindow, setSavingWindow] = useState(false);
  const [viewerImage, setViewerImage] = useState<string | null>(null);
  const [viewerTitle, setViewerTitle] = useState('');
  const [mobilePanel, setMobilePanel] = useState<'map' | 'stops'>('stops');

  usePageBreadcrumb(
    route
      ? [{ label: t.pages.routes?.title || 'Tournées', href: '/routes-table' }, { label: route.name }]
      : [{ label: t.pages.routes?.title || 'Tournées', href: '/routes-table' }]
  );

  const fetchData = useCallback(async () => {
    if (!routeId) return;
    setLoading(true);
    try {
      const res = await api.get(`/api/admin/routes/${routeId}/full`);
      const data = res.data as any;
      setRoute(data);
      const dm: Record<string, Delivery> = {};
      const tm: Record<string, TimelineEvent[]> = {};
      const pm: Record<string, ProofOfDelivery | null> = {};
      (data.stops ?? []).forEach((stop: any) => {
        const did = stop.delivery?.id || stop.deliveryId || stop.id;
        stop.deliveryId = did;
        if (stop.delivery) {
          dm[did] = {
            ...stop.delivery,
            orderId: stop.order?.id ?? stop.delivery.orderId,
            totalAmount: stop.order?.totalAmount ?? stop.delivery.totalAmount,
            totalWeightKg: stop.order?.totalWeightKg ?? stop.delivery.totalWeightKg,
            clientName: stop.order?.clientName ?? stop.delivery.clientName,
            clientPhone: stop.order?.clientPhone ?? stop.delivery.clientPhone,
            items: stop.order?.items ?? stop.delivery.items,
            erpId: stop.order?.erpOrderId ?? stop.delivery.erpId,
            priority: stop.order?.priority ?? stop.delivery.priority,
            source: stop.order?.source ?? stop.delivery.source,
          };
        }
        tm[did] = stop.statusHistory ? normalizeTimeline(stop.statusHistory) : [];
        pm[did] = stop.proofOfDelivery ? normalizePod(stop.proofOfDelivery) : null;
      });
      setDeliveryMap(dm);
      setTimelineMap(tm);
      setPodMap(pm);
    } catch {
      showErrorToast(null, 'errorDataNotLoaded');
      setRoute(null);
    } finally {
      setLoading(false);
    }
  }, [routeId]);

  useEffect(() => { void fetchData(); }, [fetchData]);

  useEffect(() => {
    if (!route?.driver?.id) return;
    api.get(`/api/admin/fleet/drivers/${route.driver.id}`)
      .then(res => setDriverOnlineStatus(res.data?.onlineStatus ?? null))
      .catch(() => {});
  }, [route?.driver?.id]);

  useEffect(() => {
    const checkFlag = () => {
      const key = `route_needs_refresh_${routeId}`;
      if (safeStorage.getItem(key)) {
        safeStorage.removeItem(key);
        void fetchData();
      }
    };
    checkFlag();
    window.addEventListener('focus', checkFlag);
    return () => window.removeEventListener('focus', checkFlag);
  }, [routeId, fetchData]);

  useEffect(() => {
    if (!route || (route.status !== 'IN_PROGRESS' && route.status !== 'VALIDATED')) return;
    const id = setInterval(() => { void fetchData(); }, 60_000);
    return () => clearInterval(id);
  }, [route?.status, fetchData]);

  useEffect(() => {
    if (!route || route.status !== 'IN_PROGRESS') return;
    const driverId = route.driver?.id;
    if (!driverId) return;

    const baseUrl = import.meta.env.VITE_WS_BASE_URL
      ?? import.meta.env.VITE_API_BASE_URL
      ?? `${window.location.protocol}//${window.location.host}`;
    const topic = '/topic/admin.routes';

    const client = new Client({
      webSocketFactory: () => new SockJS(`${baseUrl}/ws`),
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe(topic, msg => {
          try {
            const data = JSON.parse(msg.body);
            if (data.event === 'driver.location_updated' && data.driverId === driverId) {
              setRoute(prev => prev ? {
                ...prev,
                driver: { ...prev.driver!, currentLat: data.lat, currentLng: data.lng },
              } : prev);
            }
          } catch { }
        });
      },
      onStompError: () => {},
      onWebSocketClose: () => {},
    });

    client.activate();
    return () => { void client.deactivate(); };
  }, [route?.status, route?.driver?.id]);

  const orderedStops = useMemo(
    () => [...(route?.stops ?? [])].sort((a, b) => a.stopOrder - b.stopOrder),
    [route?.stops],
  );

  const mapStops = useMemo(
    () => orderedStops.map((s) => ({
      ...s,
      status: deliveryMap[s.deliveryId]?.status ?? s.status,
      routeGeometry: s.routeGeometry ?? deliveryMap[s.deliveryId]?.routeGeometry
    })),
    [orderedStops, deliveryMap],
  );

  const totalWeightKg = useMemo(
    () => orderedStops.reduce((acc, s) => acc + (s.order?.totalWeightKg ?? deliveryMap[s.deliveryId]?.totalWeightKg ?? 0), 0),
    [orderedStops, deliveryMap],
  );

  const toggleStop = (id: string) => setExpandedStops((prev) => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });
  const scrollToStop = (id: string) => {
    stopRefs.current[id]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    setExpandedStops((prev) => { const n = new Set(prev); n.add(id); return n; });
  };

  const downloadBL = async (delivery: Delivery | undefined, pod: ProofOfDelivery | null) => {
    if (!delivery) { showErrorToast(null, 'errorDataNotLoaded'); return; }
    try {
      showInfoToast('infoBlGenerating');
      const response = await api.get(`/api/admin/deliveries/${delivery.id}/bon-livraison`, { responseType: 'blob' });
      const url = window.URL.createObjectURL(new Blob([response.data], { type: 'application/pdf' }));
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `bon-${delivery.id.substring(0, 8)}.pdf`);
      document.body.appendChild(link);
      link.click();
      link.remove();
      showSuccessToast('successBlDownloaded');
    } catch (error) {
      console.error(error);
      showErrorToast(null, 'errorBlGenerationFailed');
    }
  };

  const createBackorder = async (deliveryId: string) => {
    try {
      setCreatingBackorderFor(deliveryId);
      const delivery = route?.stops?.find(s => s.deliveryId === deliveryId)?.delivery;
      await api.post(`/api/admin/deliveries/${deliveryId}/create-backorder`);
      showSuccessToast(t.apiMessages.successBackorderCreated, {
        orderId: delivery?.order?.referenceId,
        erpId: delivery?.order?.erpOrderId,
        clientName: delivery?.clientName,
      });
      await fetchData();
    } catch (err: any) {
      const delivery = route?.stops?.find(s => s.deliveryId === deliveryId)?.delivery;
      showErrorToast(
        err?.response?.data?.message,
        t.routeDetailPage?.backorderError,
        { clientName: delivery?.clientName },
      );
    } finally {
      setCreatingBackorderFor(null);
    }
  };

  const handleCancelStop = async () => {
    if (!route || !cancelStopTarget) return;
    setCancellingStop(true);
    try {
      const params = cancelStopReason.trim() ? { reason: cancelStopReason.trim() } : {};
      const response = await api.post(
        `/api/admin/routes/${route.id}/stops/${cancelStopTarget.stopId}/cancel`,
        null,
        { params },
      );
      const delivery = route.stops?.find(s => s.id === cancelStopTarget.stopId)?.delivery;
      showSuccessToast(t.apiMessages.successStopCancelled, {
        orderId: delivery?.order?.referenceId,
        erpId: delivery?.order?.erpOrderId,
        clientName: delivery?.clientName,
        routeName: route.name,
      });
      setCancelStopTarget(null);
      setCancelStopReason('');
      await fetchData();
    } catch (err: any) {
      const delivery = route.stops?.find(s => s.id === cancelStopTarget.stopId)?.delivery;
      showErrorToast(
        err?.response?.data?.message,
        t.routeDetailPage?.stopCancelError,
        { routeName: route.name, clientName: delivery?.clientName },
      );
    } finally {
      setCancellingStop(false);
    }
  };

  const handleRemoveStop = async () => {
    if (!route || !removeStopTarget) return;
    setRemovingStop(true);
    try {
      const params = new URLSearchParams();
      if (removeStopReason) params.append('reason', removeStopReason);
      const url = `/api/admin/routes/${route.id}/stops/${removeStopTarget.stopId}${params.toString() ? `?${params.toString()}` : ''}`;
      const delivery = route.stops?.find(s => s.id === removeStopTarget.stopId)?.delivery;
      await api.delete(url);
      showSuccessToast(t.apiMessages.successStopRemoved, {
        orderId: delivery?.order?.referenceId,
        erpId: delivery?.order?.erpOrderId,
        clientName: delivery?.clientName,
        routeName: route.name,
      });
      setRemoveStopTarget(null);
      setRemoveStopReason('');
      await fetchData();
    } catch (err: any) {
      const delivery = route.stops?.find(s => s.id === removeStopTarget.stopId)?.delivery;
      showErrorToast(
        err?.response?.data?.message,
        t.routeDetailPage?.stopRemoveError,
        { routeName: route.name, clientName: delivery?.clientName },
      );
    } finally {
      setRemovingStop(false);
    }
  };

  const parseHHMM = (tTime: string): number | null => {
    const parts = tTime.trim().split(':');
    if (parts.length < 2) return null;
    const h = parseInt(parts[0], 10);
    const m = parseInt(parts[1], 10);
    if (isNaN(h) || isNaN(m)) return null;
    return h * 60 + m;
  };

  const editStart = parseHHMM(editWindowStart);
  const editEnd = parseHHMM(editWindowEnd);
  const editErrStartGtEnd = editStart !== null && editEnd !== null && editStart >= editEnd;
  const editOverlaps = (() => {
    if (!route || !editWindowTarget || editStart === null || editEnd === null || editErrStartGtEnd) return [];
    const others = (route.stops ?? [])
      .filter(s => s.id !== editWindowTarget.stopId)
      .filter(s => !['COMPLETED', 'FAILED', 'PARTIAL', 'REMOVED_CANCELLED', 'REMOVED_REPLANNED'].includes(s.status));
    return others.filter(s => {
      const a = parseHHMM(s.startTimeWindow ?? '');
      const b = parseHHMM(s.endTimeWindow ?? '');
      if (a === null || b === null) return false;
      return editStart < b && a < editEnd;
    });
  })();
  const editHasConflict = editErrStartGtEnd;

  const openEditWindow = (stop: RouteStop, client: string) => {
    setEditWindowTarget({ stopId: stop.id, client });
    setEditWindowStart((stop.startTimeWindow ?? '').slice(0, 5));
    setEditWindowEnd((stop.endTimeWindow ?? '').slice(0, 5));
  };

  const handleSaveWindow = async () => {
    if (!route || !editWindowTarget || editHasConflict) return;
    setSavingWindow(true);
    try {
      const payload: Record<string, string> = {};
      if (editWindowStart) payload.startTimeWindow = editWindowStart.length === 5 ? `${editWindowStart}:00` : editWindowStart;
      if (editWindowEnd) payload.endTimeWindow = editWindowEnd.length === 5 ? `${editWindowEnd}:00` : editWindowEnd;
      const delivery = route.stops?.find(s => s.id === editWindowTarget.stopId)?.delivery;
      await api.patch(`/api/admin/routes/${route.id}/stops/${editWindowTarget.stopId}`, payload);
      showSuccessToast(t.apiMessages.successWindowUpdated, {
        orderId: delivery?.order?.referenceId,
        erpId: delivery?.order?.erpOrderId,
        clientName: delivery?.clientName,
        routeName: route.name,
      });
      setEditWindowTarget(null);
      await fetchData();
    } catch (err: any) {
      const delivery = route.stops?.find(s => s.id === editWindowTarget.stopId)?.delivery;
      showErrorToast(
        err?.response?.data?.message,
        t.routeDetailPage?.updateError,
        { routeName: route.name, clientName: delivery?.clientName },
      );
    } finally {
      setSavingWindow(false);
    }
  };

  if (loading) {
    return (
      <div className="flex flex-col h-full bg-[var(--app-bg)]">
        <div className="h-14 flex items-center gap-3 px-6 border-b border-[var(--border-color)] bg-[var(--surface)] shrink-0">
          <div className="w-8 h-8 bg-[var(--surface-hover)] rounded animate-pulse" />
          <div className="flex-1 max-w-xs h-4 bg-[var(--surface-hover)] rounded animate-pulse" />
        </div>
        <div className="border-b border-[var(--border-color)] bg-[var(--surface)] p-3 shrink-0">
          <div className="grid grid-cols-4 gap-2">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="h-12 bg-[var(--surface-hover)] rounded animate-pulse" />
            ))}
          </div>
        </div>
        <div className="flex-1 p-6">
          <SkeletonMap height={400} />
        </div>
      </div>
    );
  }

  if (!route) {
    return (
      <div className="h-full flex items-center justify-center bg-[var(--app-bg)]">
        <div className="flex flex-col items-center gap-4 text-center">
          <IconAlertCircle size={32} className="text-[var(--text-muted)]" />
          <p className="text-sm font-semibold text-[var(--text-muted)]">{t.routeDetailPage?.routeNotFound || 'Tournée introuvable.'}</p>
          <Button onClick={() => navigate('/routes-table')} variant="outline" size="sm">
            {t.routeDetailPage?.backToRoutes || 'Retour aux tournées'}
          </Button>
        </div>
      </div>
    );
  }

  const completed = route.completedStops ?? 0;
  const failed = route.failedStops ?? 0;
  const total = route.totalStops ?? orderedStops.length;
  const pct = route.progressPercent ?? (total > 0 ? ((completed + failed) / total * 100) : 0);
  const currency = orderedStops.find((s) => s.order?.currency)?.order?.currency ?? 'TND';
  const isActiveRoute = route.status === 'VALIDATED' || route.status === 'IN_PROGRESS';
  const isClosed = route?.status === 'CLOSED';

  return (
    <div className="flex flex-col h-full bg-[var(--app-bg)]" style={{ height: isClosed ? 'auto' : 'calc(100dvh - 56px)', minHeight: isClosed ? 'calc(100dvh - 56px)' : undefined, overflow: isClosed ? 'visible' : 'hidden' }}>
      <RouteHeader
        routeName={route.name}
        routeStatus={route.status}
        isOptimized={route.isOptimized}
        date={route.date}
        city={route.city}
        onBack={() => navigate('/routes-table')}
        onRefresh={() => void fetchData()}
        isRefreshing={loading}
      />

      <RouteStats
        completed={completed}
        failed={failed}
        total={total}
        progressPercent={pct}
        driverName={route.driver?.name}
        driverStatus={driverOnlineStatus ?? undefined}
        driverStatusColor={driverOnlineStatus ? (DRIVER_STATUS_COLOR[driverOnlineStatus as keyof typeof DRIVER_STATUS_COLOR] ?? DRIVER_STATUS_COLOR.OFFLINE) : undefined}
        cumulativeDelayMinutes={route.cumulativeDelayMinutes}
        routeStartDelayMinutes={route.routeStartDelayMinutes}
        onTimeCompletionRate={route.onTimeCompletionRate}
        distance={fmtDist(route.totalDistanceMeters)}
        duration={fmtDuration(route.totalDurationSeconds)}
        vehiclePlate={route.vehicle?.plate}
        vehicleType={route.vehicle?.type || route.vehicle?.name}
        totalWeightKg={totalWeightKg}
        vehicleCapacityKg={route.vehicle?.payloadKg}
      />

      <div className="lg:hidden flex shrink-0 border-b border-[var(--border-color)] bg-[var(--surface)]">
        {([['map', t.routeDetailPage?.tabMap || 'Carte'], ['stops', t.routeDetailPage?.tabStops || 'Arrêts']] as const).map(([tab, label]) => (
          <button
            key={tab}
            onClick={() => setMobilePanel(tab)}
            className={`flex-1 h-10 text-xs font-semibold transition-colors ${
              mobilePanel === tab ? 'text-[var(--brand)] border-b-2 border-[var(--brand)]' : 'text-[var(--text-muted)]'
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="flex flex-1 overflow-hidden">
        <div className={`lg:w-[45%] lg:flex lg:flex-col lg:shrink-0 lg:border-r lg:border-[var(--border-color)] bg-[var(--surface)] ${mobilePanel === 'map' ? 'flex flex-col w-full' : 'hidden lg:flex'}`}>
          <div className="flex-1 min-h-[300px]">
            <RouteTrackingMap
              stops={mapStops}
              driver={route.driver ? {
                id: route.driver.id,
                name: route.driver.name ?? '',
                lat: route.driver.currentLat,
                lng: route.driver.currentLng,
              } : null}
              depot={route.depot?.latitude != null && route.depot?.longitude != null ? {
                lat: route.depot.latitude,
                lng: route.depot.longitude,
                name: route.depot.name,
              } : null}
              height="100%"
              onStopClick={scrollToStop}
            />
          </div>

          <div className="shrink-0 border-t border-[var(--border-color)] overflow-y-auto max-h-[180px] bg-[var(--surface)] space-y-4 p-3">
            <div className="border-b border-[var(--border-color)] pb-3">
              <p className="text-xs font-semibold text-[var(--text-muted)] mb-2 uppercase">{t.routeDetailPage?.labelDepot || 'Dépôt'}</p>
              {route.depot ? (
                <div className="space-y-1">
                  <p className="text-xs font-semibold text-[var(--text-primary)]">{route.depot.name ?? '—'}</p>
                  <p className="text-xs text-[var(--text-muted)]">{route.depot.address ?? route.depot.city ?? '—'}</p>
                  {route.departureTime && <p className="text-xs text-[var(--text-muted)] font-mono">{t.routeDetailPage?.labelDeparture || 'Départ'} {fmtLong(route.departureTime)}</p>}
                </div>
              ) : (
                <p className="text-xs text-[var(--text-muted)]">{t.routeDetailPage?.noDepot || 'Aucun dépôt'}</p>
              )}
            </div>

            <div>
              <p className="text-xs font-semibold text-[var(--text-muted)] mb-2 uppercase">{t.routeDetailPage?.labelLifecycle || 'Cycle de vie'}</p>
              <div className="grid grid-cols-2 gap-2 text-xs">
                {[
                  [t.routeDetailPage?.statusCreated || 'Créée', fmtLong(route.createdAt)],
                  [t.routeDetailPage?.statusValidated || 'Validée', fmtLong(route.validatedAt)],
                  [t.routeDetailPage?.statusStarted || 'Démarrée', fmtLong(route.startedAt)],
                  [t.routeDetailPage?.statusClosed || 'Clôturée', fmtLong(route.closedAt)],
                ].map(([l, v]) => (
                  <div key={l}>
                    <p className="text-[var(--text-muted)]">{l}:</p>
                    <p className="font-mono text-[var(--text-primary)] font-semibold">{v}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>

        <div className={`flex-1 flex flex-col overflow-hidden bg-[var(--surface)] min-w-0 ${mobilePanel === 'stops' ? 'flex' : 'hidden lg:flex'}`}>
          <div className="px-4 h-11 flex items-center justify-between border-b border-[var(--border-color)] shrink-0">
            <div className="flex items-center gap-2">
              <p className="text-xs font-semibold text-[var(--text-primary)]">{t.routeDetailPage?.labelStopsSequence || 'Séquence des arrêts'}</p>
              <Badge variant="secondary" className="text-[10px] font-mono">{orderedStops.length}</Badge>
            </div>
            <div className="flex items-center gap-2">
              <p className="text-xs font-mono font-semibold text-[var(--text-muted)]">{completed}/{total}</p>
              {failed > 0 && (
                <Badge variant="destructive" className="text-[10px]">
                  {failed}
                </Badge>
              )}
            </div>
          </div>

          <div className="flex-1 overflow-y-auto bg-[var(--app-bg)] p-3">
            {orderedStops.map((stop) => {
              const delivery = deliveryMap[stop.deliveryId];
              const timeline = timelineMap[stop.deliveryId] ?? [];
              const pod = podMap[stop.deliveryId];
              const displayStatus = (delivery?.status ?? stop.status) as any;
              const sc2 = STOP_STATUS[displayStatus] ?? STOP_STATUS.SCHEDULED;
              const client = stop.order?.clientName ?? delivery?.clientName ?? '—';
              const amount = stop.order?.totalAmount ?? delivery?.totalAmount ?? 0;
              const weight = stop.order?.totalWeightKg ?? delivery?.totalWeightKg ?? 0;
              const addr = stop.deliveryAddress ?? stop.order?.dropoffAddress ?? delivery?.dropoffAddress ?? '—';
              const orderItems = stop.order?.items ?? delivery?.items ?? [];
              const orderReference = stop.order?.id ?? delivery?.orderId;
              const erpReference = stop.order?.erpOrderId ?? stop.order?.erpExternalRef ?? delivery?.erpId;
              const isExpanded = expandedStops.has(stop.id);
              const canRemove = route.status === 'DRAFT' && REMOVABLE_STOP_STATUSES.has(displayStatus);
              const canCancelStop = isActiveRoute && (displayStatus === 'PENDING' || displayStatus === 'SCHEDULED' || displayStatus === 'ARRIVED' || displayStatus === 'PICKED_UP');
              const canEditWindow = route.status === 'VALIDATED' && (displayStatus === 'PENDING' || displayStatus === 'SCHEDULED');

              return (
                <div
                  key={stop.id}
                  ref={(el) => { if (el) stopRefs.current[stop.id] = el; }}
                  className="border border-[var(--border-color)] rounded-lg bg-[var(--surface-2)] mb-2 cursor-pointer hover:bg-[var(--surface-hover)]"
                  onClick={() => toggleStop(stop.id)}
                >
                  <div className="p-3 flex items-center justify-between">
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <div className="w-6 h-6 rounded flex items-center justify-center text-xs font-bold text-white" style={{ background: sc2.dot }}>
                          {stop.stopOrder}
                        </div>
                        <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{client}</p>
                        <StatusBadge status={displayStatus} size="sm" />
                      </div>
                      <div className="flex items-center gap-2 text-xs text-[var(--text-muted)]">
                        <IconMapPin size={12} />
                        <p className="truncate">{addr}</p>
                      </div>
                    </div>
                    <div className="flex items-center gap-2 ml-2">
                      <p className="text-xs font-mono font-bold">{formatMoney(amount, stop.order?.currency ?? currency)}</p>
                      <IconChevronDown size={16} className={`transition-transform ${isExpanded ? 'rotate-180' : ''}`} />
                    </div>
                  </div>

                  {isExpanded && (
                    <div className={stopTabsStyles.expandedContainer}>
                      {/* Tab Bar */}
                      <div className={stopTabsStyles.tabBar}>
                        {(['details', 'timeline', 'pod'] as const).map((tab) => (
                          <button
                            key={tab}
                            onClick={(e) => { e.stopPropagation(); setActiveTab(prev => ({ ...prev, [stop.id]: tab })); }}
                            className={`${stopTabsStyles.tab} ${(activeTab[stop.id] ?? 'details') === tab ? stopTabsStyles.active : ''}`}
                          >
                            {tab === 'details' && (t.routeDetailPage?.tabDetails || 'Détails')}
                            {tab === 'timeline' && (t.routeDetailPage?.tabHistory || 'Historique')}
                            {tab === 'pod' && (t.routeDetailPage?.tabProof || 'Preuve')}
                          </button>
                        ))}
                      </div>

                      {/* Tab Content */}
                      <div className={stopTabsStyles.tabContent}>
                        {(activeTab[stop.id] ?? 'details') === 'details' && (
                          <div>
                            {/* Ref Links */}
                            {(erpReference || stop.deliveryId) && (
                              <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap' }} onClick={(e) => e.stopPropagation()}>
                                {erpReference && (
                                  <a href={`/deliveries/${stop.deliveryId}`} target="_blank" rel="noreferrer" style={{ textDecoration: 'none' }}>
                                    <div style={{ fontSize: 10, fontWeight: 700, fontFamily: 'monospace', padding: '4px 8px', borderRadius: 3, background: '#fef3c7', color: '#92400e', border: '1px solid #fde68a' }}>
                                      {t.routeDetailPage?.refERP || 'ERP'} · {erpReference}
                                    </div>
                                  </a>
                                )}
                                {stop.deliveryId && (
                                  <a href={`/deliveries/${stop.deliveryId}`} target="_blank" rel="noreferrer" style={{ textDecoration: 'none' }}>
                                    <div style={{ fontSize: 10, fontWeight: 700, fontFamily: 'monospace', padding: '4px 8px', borderRadius: 3, background: 'var(--surface-3)', color: 'var(--text-soft)', border: '1px solid var(--border-color)' }}>
                                      {t.routeDetailPage?.refDelivery || 'LIV'} · {stop.deliveryId.slice(0, 8).toUpperCase()}
                                    </div>
                                  </a>
                                )}
                              </div>
                            )}

                            {/* Info Grid */}
                            <div className={stopTabsStyles.infoGrid}>
                              {orderReference && <div className={stopTabsStyles.infoRow}><span className={stopTabsStyles.label}>{t.routeDetailPage?.labelOrder || 'Commande'}:</span><span className={stopTabsStyles.value}>{orderReference}</span></div>}
                              {erpReference && <div className={stopTabsStyles.infoRow}><span className={stopTabsStyles.label}>{t.routeDetailPage?.labelErpRef || 'Réf. ERP'}:</span><span className={stopTabsStyles.value}>{erpReference}</span></div>}
                              {stop.order?.clientPhone && (
                                <div className={`${stopTabsStyles.infoRow} ${stopTabsStyles.soft}`}>
                                  <IconPhone size={12} style={{ flexShrink: 0 }} />
                                  <span>{stop.order.clientPhone}</span>
                                </div>
                              )}
                              <div className={`${stopTabsStyles.infoRow} ${stopTabsStyles.strong}`}>
                                <span className={stopTabsStyles.label}>{t.routeDetailPage?.labelWeight || 'Poids'}:</span>
                                <span className={stopTabsStyles.value}>{weight.toFixed(2)} {t.routeDetailPage?.unitKg || 'kg'}</span>
                                {stop.order?.totalQuantity && <span style={{ marginLeft: 8 }}>| {t.routeDetailPage?.labelQty || 'Qté'}: {stop.order.totalQuantity}</span>}
                              </div>
                              {stop.order?.source && <div className={stopTabsStyles.infoRow}><span className={stopTabsStyles.label}>{t.routeDetailPage?.labelSource || 'Source'}:</span><span>{stop.order.source}</span></div>}
                              {(stop.routeDistanceKm != null || stop.routeDurationMinutes != null) && (
                                <div className={stopTabsStyles.infoRow}>
                                  {stop.routeDistanceKm != null && `${Number(stop.routeDistanceKm).toFixed(1)} km`}
                                  {stop.routeDistanceKm && stop.routeDurationMinutes && ` · `}
                                  {stop.routeDurationMinutes != null && `${stop.routeDurationMinutes} ${t.routeDetailPage?.unitMin || 'min'}`}
                                </div>
                              )}
                              {(stop.startTimeWindow || stop.endTimeWindow) && (
                                <div className={stopTabsStyles.infoRow}>
                                  <span className={stopTabsStyles.label}>{t.routeDetailPage?.labelTimeWindow || 'Fenêtre horaire'}:</span>
                                  <span className={stopTabsStyles.value}>{fmtTimeWindow(stop.startTimeWindow)} - {fmtTimeWindow(stop.endTimeWindow)}</span>
                                </div>
                              )}
                              {stop.order?.deliveryInstructions && <div className={stopTabsStyles.box}><strong>{t.routeDetailPage?.labelInstructions || 'Instructions'}:</strong> {stop.order.deliveryInstructions}</div>}
                              {stop.notes && <div className={stopTabsStyles.box}><strong>{t.routeDetailPage?.labelNotes || 'Note'}:</strong> {stop.notes}</div>}
                            </div>

                            {/* Items Table */}
                            {orderItems && orderItems.length > 0 && (
                              <div>
                                <div style={{ fontSize: 11, fontWeight: 700, marginTop: 12, marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.5px', color: 'var(--text-strong)', opacity: 0.85 }}>
                                  {t.routeDetailPage?.labelArticles || 'Articles'} ({orderItems.length})
                                </div>
                                <div className={stopTabsStyles.itemsTableWrapper}>
                                  <table>
                                    <thead>
                                      <tr>
                                        <th>{t.routeDetailPage?.tableArticle || 'Article'}</th>
                                        <th>{t.routeDetailPage?.tableOrdered || 'Commandé'}</th>
                                        <th>{t.routeDetailPage?.tableDelivered || 'Livré'}</th>
                                        <th>{t.routeDetailPage?.tableStatus || 'Statut'}</th>
                                        <th>{t.routeDetailPage?.tableUnitPrice || 'Prix unit.'}</th>
                                      </tr>
                                    </thead>
                                    <tbody>
                                      {orderItems.map((item, idx) => {
                                        const isPostPod  = item.quantityDone != null;
                                        const qtyDone    = item.quantityDone ?? item.quantity ?? 0;
                                        const qtyPlanned = item.quantity ?? 0;
                                        const outcome    = item.outcome ?? (isPostPod ? (qtyDone > 0 ? 'DELIVERED' : 'REFUSED') : null);
                                        const badgeConfig = outcome ? BadgeStatusMap[outcome as keyof typeof BadgeStatusMap] : null;

                                        return (
                                          <>
                                            <tr key={`${item.name}-${idx}`}>
                                              <td className={stopTabsStyles.articleName}>{item.name}</td>
                                              <td>×{qtyPlanned}</td>
                                              <td style={{ color: isPostPod && qtyDone < qtyPlanned ? '#d97706' : 'var(--text-soft)' }}>
                                                {isPostPod ? `×${qtyDone}` : '—'}
                                              </td>
                                              <td>
                                                {badgeConfig ? (
                                                  <span className={`${stopTabsStyles.statusBadgeContainer} ${stopTabsStyles[badgeConfig.className]}`}>
                                                    <span className={stopTabsStyles.svgIcon}><badgeConfig.icon /></span>
                                                    <span className={stopTabsStyles.label}>{badgeConfig.label}</span>
                                                    {item.reason && <span className={stopTabsStyles.reason}>{t.itemReasons[item.reason] ?? item.reason}</span>}
                                                  </span>
                                                ) : '—'}
                                              </td>
                                              <td>{formatMoney(item.unitPrice ?? item.price, stop.order?.currency ?? currency)}</td>
                                            </tr>
                                            {item.comment && (
                                              <tr key={`${item.name}-${idx}-comment`} className={stopTabsStyles.commentRow}>
                                                <td colSpan={5} dangerouslySetInnerHTML={{ __html: item.comment }} />
                                              </tr>
                                            )}
                                          </>
                                        );
                                      })}
                                    </tbody>
                                    <tfoot>
                                      <tr>
                                        <td colSpan={4}>{t.routeDetailPage?.labelTotal || 'Total'}</td>
                                        <td>{formatMoney(amount, stop.order?.currency ?? currency)}</td>
                                      </tr>
                                    </tfoot>
                                  </table>
                                </div>
                              </div>
                            )}

                            {(stop.status === 'PARTIAL' || delivery?.status === 'PARTIALLY_DELIVERED') && (
                              <button
                                style={{ marginTop: 12, padding: '8px 12px', fontSize: 11, fontWeight: 600, border: '1px solid var(--border-color)', background: 'var(--surface-3)', color: 'var(--text-strong)', borderRadius: 3, cursor: 'pointer' }}
                                onClick={(e) => { e.stopPropagation(); void createBackorder(stop.deliveryId); }}
                                disabled={creatingBackorderFor === stop.deliveryId}
                              >
                                {creatingBackorderFor === stop.deliveryId ? (t.routeDetailPage?.loading || 'En cours...') : (t.routeDetailPage?.createBackorder || 'Créer backorder')}
                              </button>
                            )}
                          </div>
                        )}

                        {(activeTab[stop.id] ?? 'details') === 'timeline' && (
                          <div>
                            {timeline.length === 0 ? (
                              <div className={stopTabsStyles.emptyState}>
                                <IconAlertCircle size={12} />
                                <span>{t.empty.history || 'Aucun événement enregistré'}</span>
                              </div>
                            ) : (
                              <div className={stopTabsStyles.timelineContainer}>
                                {timeline.map((ev, idx) => {
                                  const dotColor = STATUS_COLORS[ev.status] ?? '#94a3b8';
                                  return (
                                    <div key={`${ev.timestamp}-${idx}`} className={stopTabsStyles.timelineItem}>
                                      <div className={stopTabsStyles.dotContainer}>
                                        <div className={stopTabsStyles.dot} style={{ background: dotColor }} />
                                      </div>
                                      <div className={stopTabsStyles.content}>
                                        <div className={stopTabsStyles.header}>
                                          <span className={stopTabsStyles.status} style={{ color: dotColor }}>
                                            {t.statusLabels[ev.status] ?? ev.status}
                                          </span>
                                          <span className={stopTabsStyles.timestamp}>{fmt(ev.timestamp)}</span>
                                        </div>
                                        {ev.actor && <div className={stopTabsStyles.actor}>{t.routeDetailPage?.by || 'par'} {t.actors[ev.actor] ?? ev.actor}</div>}
                                        {cleanNote(ev.note, t) && (
                                          <div className={stopTabsStyles.note} dangerouslySetInnerHTML={{ __html: cleanNote(ev.note, t) ?? '' }} />
                                        )}
                                        {(ev.eventParams as any)?.reason && (
                                          <div className={stopTabsStyles.note}>Raison: <em>{(ev.eventParams as any).reason}</em></div>
                                        )}
                                      </div>
                                    </div>
                                  );
                                })}
                              </div>
                            )}
                          </div>
                        )}

                        {(activeTab[stop.id] ?? 'details') === 'pod' && (
                          <div>
                            {!pod ? (
                              <div className={stopTabsStyles.emptyState}>
                                <IconAlertCircle size={12} />
                                <span>{t.routeDetailPage?.noPodAvailable || 'Preuve de livraison indisponible'}</span>
                              </div>
                            ) : (
                              <div className={stopTabsStyles.podContainer}>
                                <div className={stopTabsStyles.metadata}>
                                  <div className={stopTabsStyles.timestamp}>{fmtLong(pod.timestamp)}</div>
                                  {(pod.latitude || pod.longitude) && (
                                    <div className={stopTabsStyles.coords}>
                                      {Number(pod.latitude).toFixed(5)}, {Number(pod.longitude).toFixed(5)}
                                    </div>
                                  )}
                                  {pod.comment && <div className={stopTabsStyles.comment}>{pod.comment}</div>}
                                </div>

                                {(mediaSrc(pod.signatureUrl, pod.signatureBase64) || mediaSrc(pod.photoUrl, pod.photoBase64)) && (
                                  <div className={stopTabsStyles.imagesGrid}>
                                    {mediaSrc(pod.signatureUrl, pod.signatureBase64) && (
                                      <div
                                        className={stopTabsStyles.podCard}
                                        onClick={(e) => { e.stopPropagation(); setViewerTitle(t.routeDetailPage?.signedBL || 'BL Signé'); setViewerImage(mediaSrc(pod.signatureUrl, pod.signatureBase64) ?? null); }}
                                      >
                                        <div className={stopTabsStyles.label}>{t.routeDetailPage?.signedBL || 'BL Signé'}</div>
                                        <img src={mediaSrc(pod.signatureUrl, pod.signatureBase64) ?? ''} alt="BL Signé" />
                                      </div>
                                    )}
                                    {mediaSrc(pod.photoUrl, pod.photoBase64) && (
                                      <div
                                        className={stopTabsStyles.podCard}
                                        onClick={(e) => { e.stopPropagation(); setViewerTitle(t.routeDetailPage?.photoPod || 'Photo POD'); setViewerImage(mediaSrc(pod.photoUrl, pod.photoBase64) ?? null); }}
                                      >
                                        <div className={stopTabsStyles.label}>{t.routeDetailPage?.photo || 'Photo'}</div>
                                        <img src={mediaSrc(pod.photoUrl, pod.photoBase64) ?? ''} alt="Photo POD" />
                                      </div>
                                    )}
                                  </div>
                                )}

                                {pod && (
                                  <div className={stopTabsStyles.actions}>
                                    <button onClick={(e) => { e.stopPropagation(); void downloadBL(delivery, pod); }}>
                                      <IconFileText size={11} />
                                      {t.routeDetailPage?.deliveryNote || 'Bon de livraison'}
                                    </button>
                                  </div>
                                )}
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  )}

                  {isExpanded && (
                    <div className="border-t border-[var(--border-color)] p-2 bg-[var(--surface-2)] flex gap-2">
                      {canEditWindow && (
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={(e) => { e.stopPropagation(); openEditWindow(stop, client); }}
                            >
                              <IconPencil size={14} className="mr-1" /> {t.routeDetailPage?.buttonTimeWindow || 'Fenêtre'}
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent side="top">
                            {t.routeDetailPage?.tooltipEditWindow || 'Modifier la fenêtre horaire de livraison pour cet arrêt.'}
                          </TooltipContent>
                        </Tooltip>
                      )}
                      {canRemove && (
                        <Button
                          size="sm"
                          variant="destructive"
                          onClick={(e) => { e.stopPropagation(); setRemoveStopTarget({ stopId: stop.id, client }); }}
                        >
                          <IconX size={14} className="mr-1" /> {t.routeDetailPage?.buttonRemove || 'Retirer'}
                        </Button>
                      )}
                      {canCancelStop && (
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <Button
                              size="sm"
                              variant="destructive"
                              onClick={(e) => { e.stopPropagation(); setCancelStopTarget({ stopId: stop.id, client, isPickedUp: stop.status === 'PICKED_UP' }); setCancelStopReason(''); }}
                            >
                              <IconBan size={14} className="mr-1" /> {t.routeDetailPage?.buttonRemoveRoute || 'Retirer de la tournée'}
                            </Button>
                          </TooltipTrigger>
                          <TooltipContent side="top">
                            {t.routeDetailPage?.tooltipRemoveStop || 'Retirer ce stop de la route — la livraison retournera au pool non planifié pour redéploiement. Le chauffeur sera notifié immédiatement.'}
                          </TooltipContent>
                        </Tooltip>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>

      {route?.status === 'CLOSED' && typeof routeId === 'string' && (
        <RouteReportSection routeId={routeId} />
      )}

      <ConfirmModal
        open={cancelStopTarget !== null}
        title={t.routeDetailPage?.modalRemoveStopTitle || 'Retirer ce stop de la route?'}
        description={`${t.routeDetailPage?.tooltipRemoveStop || 'La livraison retournera au pool non planifié'}\n\nClient: ${cancelStopTarget?.client}`}
        variant="danger"
        reasonLabel={t.routeDetailPage?.reason || 'Raison'}
        reasonPlaceholder={t.routeDetailPage?.reasonPlaceholder || 'Expliquez pourquoi vous retirez ce stop...'}
        reason={cancelStopReason}
        onReasonChange={setCancelStopReason}
        reasonRequired={true}
        confirmLabel={t.routeDetailPage?.modalRemove || 'Retirer'}
        cancelLabel={t.routeDetailPage?.modalClose || 'Fermer'}
        loading={cancellingStop}
        onConfirm={() => void handleCancelStop()}
        onCancel={() => { setCancelStopTarget(null); setCancelStopReason(''); }}
      />

      <ConfirmModal
        open={removeStopTarget !== null}
        title={t.routeDetailPage?.modalRemoveStopTitle || 'Retirer ce stop'}
        description={`${t.routeDetailPage?.modalRemoveStopDesc || 'Retirer'} "${removeStopTarget?.client}"`}
        variant="danger"
        reasonLabel={t.routeDetailPage?.reason || 'Raison'}
        reasonPlaceholder={t.routeDetailPage?.reasonPlaceholder || 'Expliquez pourquoi vous retirez ce stop...'}
        reason={removeStopReason}
        onReasonChange={setRemoveStopReason}
        reasonRequired={true}
        confirmLabel={t.routeDetailPage?.modalRemove || 'Retirer'}
        cancelLabel={t.routeDetailPage?.modalCancel || 'Annuler'}
        loading={removingStop}
        onConfirm={() => void handleRemoveStop()}
        onCancel={() => { setRemoveStopTarget(null); setRemoveStopReason(''); }}
      />

      <Dialog open={editWindowTarget !== null} onOpenChange={(open) => !open && setEditWindowTarget(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t.routeDetailPage?.labelTimeWindow || 'Fenêtre horaire'} — {editWindowTarget?.client}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="text-xs font-semibold">{t.routeDetailPage?.labelStart || 'Début'}</label>
                <Input
                  type="time"
                  value={editWindowStart}
                  onChange={(e) => setEditWindowStart(e.target.value)}
                  className="mt-1"
                />
              </div>
              <div>
                <label className="text-xs font-semibold">{t.routeDetailPage?.labelEnd || 'Fin'}</label>
                <Input
                  type="time"
                  value={editWindowEnd}
                  onChange={(e) => setEditWindowEnd(e.target.value)}
                  className="mt-1"
                />
              </div>
            </div>

            {editErrStartGtEnd && (
              <div className="text-xs text-rose-600 bg-rose-50 dark:bg-rose-950/30 dark:text-rose-400 p-2.5 rounded border border-rose-200 dark:border-rose-900/50">
                {t.apiMessages?.errorWindowInvalid || 'Fenêtre horaire invalide'}
              </div>
            )}

            {editOverlaps.length > 0 && (
              <div className="text-xs text-amber-600 bg-amber-50 dark:bg-amber-950/30 dark:text-amber-400 p-2.5 rounded border border-amber-200 dark:border-amber-900/50">
                {t.routeDetailPage?.overlapWarning || "⚠️ Ce créneau chevauche d'autres arrêts sur la tournée."}
              </div>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditWindowTarget(null)}>{t.routeDetailPage?.modalCancel || 'Annuler'}</Button>
            <Button onClick={() => void handleSaveWindow()} disabled={editErrStartGtEnd || savingWindow}>{t.routeDetailPage?.modalSave || 'Enregistrer'}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={!!viewerImage} onOpenChange={(open) => !open && setViewerImage(null)}>
        <DialogContent className="max-w-lg">
          <DialogHeader>
            <DialogTitle>{viewerTitle}</DialogTitle>
          </DialogHeader>
          {viewerImage && (
            <img src={viewerImage} alt={viewerTitle} className="w-full h-auto max-h-96 object-contain" />
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
