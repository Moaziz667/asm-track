import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { safeStorage } from '@/lib/storage';
import { useT } from '@/lib/i18n/LocaleContext';
import { useRealtimeEvent } from '@/components/RealtimeProvider';
import { showSuccessToast, showErrorToast, showInfoToast } from '@/lib/ui/toast-service';
import { Delivery, DeliverySource, ProofOfDelivery, TimelineEvent } from '@/types';
import { normalizeTimeline, normalizePod } from './helpers';
import type { RouteDetail, RouteStop } from './types';

// Raw `/full` stop payload — carries richer fields (statusHistory, POD, nested order) than RouteStop.
type RawFullStop = {
  id?: string; deliveryId?: string;
  delivery?: Delivery;
  order?: {
    id?: string; erpOrderId?: string; priority?: string; source?: DeliverySource;
    totalAmount?: number; totalWeightKg?: number; clientName?: string; clientPhone?: string;
    items?: Delivery['items']; referenceId?: string;
  };
  statusHistory?: TimelineEvent[];
  proofOfDelivery?: ProofOfDelivery | null;
  [k: string]: unknown;
};

/** All data, realtime, derived metrics and mutation handlers for the route-details page.
 *  Extracted verbatim from RouteDetailsPage so the page is a pure layout/orchestrator.
 *  The IN_PROGRESS driver-GPS STOMP socket is preserved exactly. */
export function useRouteData(routeId: string | undefined) {
  const t = useT();
  const stopRefs = useRef<Record<string, HTMLDivElement | null>>({});

  const [route, setRoute] = useState<RouteDetail | null>(null);
  const [driverOnlineStatus, setDriverOnlineStatus] = useState<string | null>(null);
  const [driverLastSeen, setDriverLastSeen] = useState<string | null>(null);
  const [deliveryMap, setDeliveryMap] = useState<Record<string, Delivery>>({});
  const [timelineMap, setTimelineMap] = useState<Record<string, TimelineEvent[]>>({});
  const [podMap, setPodMap] = useState<Record<string, ProofOfDelivery | null>>({});
  const [loading, setLoading] = useState(true);
  const [expandedStops, setExpandedStops] = useState<Set<string>>(new Set());
  const [activeTab, setActiveTab] = useState<Record<string, 'details' | 'pod'>>({});
  const [cancelStopTarget, setCancelStopTarget] = useState<{ stopId: string; client: string; isPickedUp: boolean } | null>(null);
  const [cancelStopReason, setCancelStopReason] = useState('');
  const [cancellingStop, setCancellingStop] = useState(false);
  const [removeStopTarget, setRemoveStopTarget] = useState<{ stopId: string; client: string } | null>(null);
  const [removeStopReason, setRemoveStopReason] = useState('');
  const [removingStop, setRemovingStop] = useState(false);
  const [editWindowTarget, setEditWindowTarget] = useState<{ stopId: string; client: string } | null>(null);
  const [editWindowStart, setEditWindowStart] = useState('');
  const [editWindowEnd, setEditWindowEnd] = useState('');
  const [savingWindow, setSavingWindow] = useState(false);
  const [viewerImage, setViewerImage] = useState<string | null>(null);
  const [viewerTitle, setViewerTitle] = useState('');
  const [mobilePanel, setMobilePanel] = useState<'map' | 'stops'>('stops');

  const fetchData = useCallback(async () => {
    if (!routeId) return;
    setLoading(true);
    try {
      const res = await api.get(`/admin/routes/${routeId}/full`);
      const data = res.data as RouteDetail;
      setRoute(data);
      const dm: Record<string, Delivery> = {};
      const tm: Record<string, TimelineEvent[]> = {};
      const pm: Record<string, ProofOfDelivery | null> = {};
      ((data.stops ?? []) as unknown as RawFullStop[]).forEach((stop) => {
        const did = stop.delivery?.id || stop.deliveryId || stop.id;
        if (!did) return;
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

  const driverId = route?.driver?.id;

  const fetchDriverStatus = useCallback(() => {
    if (!driverId) return;
    api.get(`/admin/fleet/drivers/${driverId}`)
      .then(res => {
        setDriverOnlineStatus(res.data?.onlineStatus ?? null);
        setDriverLastSeen(res.data?.lastLocationAt ?? null);
      })
      .catch(() => {});
  }, [driverId]);

  useEffect(() => { fetchDriverStatus(); }, [fetchDriverStatus]);

  /**
   * Follow the driver's availability live instead of freezing it at page load.
   *
   * <p>This was fetched once, keyed on the driver id, and never again — so a dispatcher watching a
   * running route saw "hors service" for a driver who had gone on duty minutes earlier, and had no
   * way to tell a stale reading from a real one. The sixty-second poll next to it refreshes the
   * route, not the driver.
   *
   * <p>The status is taken from the event rather than refetched: the payload already carries it,
   * and a round-trip per event would put the page's freshness at the mercy of a request it does not
   * need to make. {@code lastSeen} is left alone — availability is not a location sighting, and
   * overwriting it here would make an idle driver look like he had just reported in.
   */
  useRealtimeEvent(['driver.status_changed'], evt => {
    if (!driverId || evt.payload?.driverId !== driverId) return;
    const status = evt.payload?.status;
    if (typeof status === 'string' && status) setDriverOnlineStatus(status);
  });

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

  /**
   * Follow the driver's marker on the route map.
   *
   * <p>This opened its own SockJS connection to the tenant's admin.routes topic — the very topic
   * {@link RealtimeProvider} is already subscribed to — decoded the JWT again to build the
   * destination, and then read `data.event` and `data.lat` straight off the message. The server
   * sends a CloudEvent, so the coordinates sit under `data` and both reads returned undefined: a
   * second socket, opened per visit, delivering nothing. The marker sat wherever the initial fetch
   * had put it for the whole round.
   *
   * <p>Going through the shared provider fixes the shape and removes the duplicate connection; it
   * unwraps the envelope and exposes the payload with `.event` already set.
   */
  useRealtimeEvent(['driver.location_updated'], evt => {
    if (route?.status !== 'IN_PROGRESS' || !driverId) return;
    const { driverId: movedId, lat, lng, timestamp } = evt.payload ?? {};
    if (movedId !== driverId || lat == null || lng == null) return;
    setRoute(prev => prev?.driver
      ? { ...prev, driver: { ...prev.driver, currentLat: Number(lat), currentLng: Number(lng) } }
      : prev);
    setDriverLastSeen(typeof timestamp === 'string' ? timestamp : new Date().toISOString());
  });

  /**
   * Refresh the round when one of its stops moves.
   *
   * <p>Stop and delivery statuses arrived only on the sixty-second poll above, so a parcel handed
   * over in the field could sit as "en transit" for a full minute on the dispatcher's screen — and
   * the poll only runs while the route is IN_PROGRESS or VALIDATED, leaving every other state with
   * no refresh at all.
   *
   * <p>Filtered to this route. Delivery events carry the route they belong to, and the payload's
   * deliveryId is matched against the stops as a fallback for the moment a parcel is being detached
   * (reassigned away), when the server-side lookup no longer resolves a route. Without the filter
   * every delivery event in the tenant would refetch a page it has nothing to do with.
   *
   * <p>A full refetch rather than a local patch: the page shows the stop's timeline, its proof of
   * delivery and its nested order, none of which travel on the event. Debounced, because a POD
   * submission emits several events in a row and they describe a single change.
   */
  const stopRtTimer = useRef<number | null>(null);
  useRealtimeEvent(
    ['delivery.picked_up', 'delivery.in_transit', 'delivery.completed', 'delivery.failed',
     'delivery.cancelled', 'delivery.reassigned', 'delivery.reassigned_away', 'delivery.replanned',
     'delivery.handoff_required', 'delivery.handoff_confirmed', 'delivery.scheduled',
     'route.validated', 'route.cancelled', 'route.stop_added', 'route.stop_removed',
     'route.schedule_changed',
     // Route-execution events are SCREAMING_CASE, not the delivery.* convention — subscribing by
     // name means the two spellings have to be listed side by side or half the lifecycle is missed.
     //
     // PICKUP_CONFIRMED is the one that matters most here: loading at the depot flips every parcel
     // of that depot from SCHEDULED to PICKED_UP in one sweep, inside RouteExecutionService, which
     // emits this single route-level event instead of one delivery.picked_up per parcel. So the
     // per-parcel pickup refreshed the page live while the depot swipe — the normal way a round
     // starts — did not, which is exactly the "planifié → chargé" gap.
     'PICKUP_CONFIRMED', 'ROUTE_STARTED', 'ROUTE_UPDATED', 'STOP_ADDED', 'STOP_REMOVED'],
    evt => {
      if (!routeId) return;
      const p = evt.payload ?? {};
      const mine = p.routeId === routeId
        || (p.deliveryId != null && (route?.stops ?? []).some(s => s.deliveryId === p.deliveryId));
      if (!mine) return;
      if (stopRtTimer.current != null) return;
      stopRtTimer.current = window.setTimeout(() => {
        stopRtTimer.current = null;
        void fetchData();
      }, 1200);
    },
  );

  useEffect(() => () => {
    if (stopRtTimer.current != null) window.clearTimeout(stopRtTimer.current);
  }, []);

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

  const toggleStop = (id: string) => setExpandedStops((prev) => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const scrollToStop = (id: string) => {
    stopRefs.current[id]?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    setExpandedStops((prev) => { const n = new Set(prev); n.add(id); return n; });
  };

  const downloadBL = async (delivery: Delivery | undefined, _pod: ProofOfDelivery | null) => {
    if (!delivery) { showErrorToast(null, 'errorDataNotLoaded'); return; }
    try {
      showInfoToast('infoBlGenerating');
      const response = await api.get(`/admin/deliveries/${delivery.id}/bon-livraison`, { responseType: 'blob' });
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


  const handleCancelStop = async () => {
    if (!route || !cancelStopTarget) return;
    setCancellingStop(true);
    try {
      const params = cancelStopReason.trim() ? { reason: cancelStopReason.trim() } : {};
      await api.post(
        `/admin/routes/${route.id}/stops/${cancelStopTarget.stopId}/cancel`,
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
    } catch (err) {
      const delivery = route.stops?.find(s => s.id === cancelStopTarget.stopId)?.delivery;
      showErrorToast(
        err,
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
      const url = `/admin/routes/${route.id}/stops/${removeStopTarget.stopId}${params.toString() ? `?${params.toString()}` : ''}`;
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
    } catch (err) {
      const delivery = route.stops?.find(s => s.id === removeStopTarget.stopId)?.delivery;
      showErrorToast(
        err,
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
      await api.patch(`/admin/routes/${route.id}/stops/${editWindowTarget.stopId}`, payload);
      showSuccessToast(t.apiMessages.successWindowUpdated, {
        orderId: delivery?.order?.referenceId,
        erpId: delivery?.order?.erpOrderId,
        clientName: delivery?.clientName,
        routeName: route.name,
      });
      setEditWindowTarget(null);
      await fetchData();
    } catch (err) {
      const delivery = route.stops?.find(s => s.id === editWindowTarget.stopId)?.delivery;
      showErrorToast(
        err,
        t.routeDetailPage?.updateError,
        { routeName: route.name, clientName: delivery?.clientName },
      );
    } finally {
      setSavingWindow(false);
    }
  };

  return {
    route, loading, fetchData,
    driverOnlineStatus, driverLastSeen,
    deliveryMap, timelineMap, podMap,
    orderedStops, mapStops, totalWeightKg,
    stopRefs, expandedStops, toggleStop, scrollToStop,
    activeTab, setActiveTab,
    downloadBL,
    cancelStopTarget, setCancelStopTarget, cancelStopReason, setCancelStopReason, cancellingStop, handleCancelStop,
    removeStopTarget, setRemoveStopTarget, removeStopReason, setRemoveStopReason, removingStop, handleRemoveStop,
    editWindowTarget, setEditWindowTarget, editWindowStart, setEditWindowStart, editWindowEnd, setEditWindowEnd,
    savingWindow, handleSaveWindow, openEditWindow, editErrStartGtEnd, editOverlaps,
    viewerImage, setViewerImage, viewerTitle, setViewerTitle,
    mobilePanel, setMobilePanel,
  };
}
