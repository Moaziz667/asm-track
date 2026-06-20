import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { api } from '@/lib/api';
import { safeStorage } from '@/lib/storage';
import { useT } from '@/lib/LocaleContext';
import { showSuccessToast, showErrorToast, showInfoToast } from '@/lib/toast-service';
import { Delivery, ProofOfDelivery, TimelineEvent } from '@/types';
import { normalizeTimeline, normalizePod } from './helpers';
import type { RouteDetail, RouteStop } from './types';

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
      .then(res => {
        setDriverOnlineStatus(res.data?.onlineStatus ?? null);
        setDriverLastSeen(res.data?.lastLocationAt ?? null);
      })
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
              setDriverLastSeen(data.timestamp ?? new Date().toISOString());
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

  const downloadBL = async (delivery: Delivery | undefined, _pod: ProofOfDelivery | null) => {
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


  const handleCancelStop = async () => {
    if (!route || !cancelStopTarget) return;
    setCancellingStop(true);
    try {
      const params = cancelStopReason.trim() ? { reason: cancelStopReason.trim() } : {};
      await api.post(
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
