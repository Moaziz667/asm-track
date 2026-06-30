'use client';

import React, { useEffect, useMemo, useState, useRef, useCallback, createContext, useContext, ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { type DragEndEvent, type DragStartEvent } from '@dnd-kit/core';
import { api } from '@/lib/api';
import { canManageRoutes, getCurrentRole } from '@/lib/auth';
import { useT } from '@/lib/LocaleContext';
import type { Driver } from '@/types';
import type { 
  RouteItem, 
  RouteStop, 
  DeliveryOption, 
  VehicleItem, 
  DepotItem, 
  StopWindowDraft, 
  OptimizeSuggestion 
} from '../types';

/**
 * Multi-depot precedence rule (mirrors the backend's assertPickupPrecedence): for an ordered
 * list of stops, every DELIVERY sourced from a non-home depot must come after that depot's
 * PICKUP stop. Home-depot deliveries (no PICKUP stop for their depot) are unconstrained.
 */
export function isPickupPrecedenceValid(stops: RouteStop[]): boolean {
  const pickupIndexByDepot = new Map<string, number>();
  stops.forEach((s, i) => {
    if (s.stopType === 'PICKUP' && s.sourceDepotId) pickupIndexByDepot.set(s.sourceDepotId, i);
  });
  if (pickupIndexByDepot.size === 0) return true;
  for (let i = 0; i < stops.length; i++) {
    const s = stops[i];
    if (s.stopType === 'PICKUP') continue;
    const depot = s.sourceDepotId;
    if (depot && pickupIndexByDepot.has(depot) && pickupIndexByDepot.get(depot)! > i) {
      return false;
    }
  }
  return true;
}

// 8-color palette for per-route identity (sidebar dot, map pin, polyline,
// timeline block). Brand orange (#FF5722) is intentionally excluded so the
// active-route accent stays unambiguous.
export const ROUTE_COLORS = [
  '#2563eb', // blue
  '#16a34a', // green
  '#7c3aed', // purple
  '#dc2626', // red
  '#0d9488', // teal
  '#db2777', // pink
  '#d97706', // amber
  '#4f46e5', // indigo
];

export const colorForRouteIndex = (idx: number) => ROUTE_COLORS[idx % ROUTE_COLORS.length];

export const SERVICE_MINUTES = 10;

export const toShortTime = (value?: string) => (value ? String(value).slice(0, 5) : '');

const addMinutesToTime = (timeStr: string, minutes: number) => {
  if (!timeStr) return '';
  const [h, m] = timeStr.split(':').map(Number);
  const date = new Date();
  date.setHours(h, m + minutes, 0, 0);
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
};

export function useRouteBuilder() {
  const t = useT();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const searchParam = searchParams?.get('search') ?? '';
  const isMounted = useRef(false);

  useEffect(() => {
    isMounted.current = true;
    return () => { isMounted.current = false; };
  }, []);

  // Auth check
  useEffect(() => {
    if (!canManageRoutes(getCurrentRole())) {
      navigate('/dashboard', { replace: true });
    }
  }, [navigate]);

  // State
  const [loading, setLoading] = useState(true);
  const [routes, setRoutes] = useState<RouteItem[]>([]);
  const [drivers, setDrivers] = useState<Driver[]>([]);
  const [vehicles, setVehicles] = useState<VehicleItem[]>([]);
  const [availableDrivers, setAvailableDrivers] = useState<Driver[]>([]);
  const [availableVehicles, setAvailableVehicles] = useState<VehicleItem[]>([]);
  const [depots, setDepots] = useState<DepotItem[]>([]);
  const [waitingDeliveries, setWaitingDeliveries] = useState<DeliveryOption[]>([]);
  const [selectedRouteId, setSelectedRouteId] = useState<string | null>(null);
  const [selectedOrderIds, setSelectedOrderIds] = useState<string[]>([]);
  const [stopWindows, setStopWindows] = useState<Record<string, StopWindowDraft>>({});

  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [batchAssigning, setBatchAssigning] = useState(false);
  const [savingWindows, setSavingWindows] = useState(false);
  const [validatingRouteId, setValidatingRouteId] = useState<string | null>(null);
  const [confirmValidateRouteId, setConfirmValidateRouteId] = useState<string | null>(null);
  const [removingStopId, setRemovingStopId] = useState<string | null>(null);
  const [deletingRouteId, setDeletingRouteId] = useState<string | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [savingSettings, setSavingSettings] = useState(false);
  const [optimizing, setOptimizing] = useState(false);
  const [suggestion, setSuggestion] = useState<OptimizeSuggestion | null>(null);
  const [showRouteTrajet, setShowRouteTrajet] = useState(true);
  const [showSuggestionTrajet, setShowSuggestionTrajet] = useState(true);
  const [selectedRouteZoneLabel, setSelectedRouteZoneLabel] = useState<string>('');
  const [routeWeightById, setRouteWeightById] = useState<Record<string, number>>({});
  const [confirmDeleteRouteId, setConfirmDeleteRouteId] = useState<string | null>(null);
  const [deliverySearch, setDeliverySearch] = useState(searchParam);
  const [orderQuickView, setOrderQuickView] = useState<'all' | 'today' | 'thisWeek'>('all');
  const [optimizationStartTime, setOptimizationStartTime] = useState('08:00');

  // Phase 2: date filter, multi-select, batch optimize
  const [routesDate, setRoutesDate] = useState<string | null>(
    new Date().toISOString().slice(0, 10),
  );
  const [batchSelectedRouteIds, setBatchSelectedRouteIds] = useState<string[]>([]);
  const [batchOptimizing, setBatchOptimizing] = useState(false);
  const [lockingRouteId, setLockingRouteId] = useState<string | null>(null);
  const [activeDragId, setActiveDragId] = useState<string | null>(null);
  const [selectedStopIds, setSelectedStopIds] = useState<string[]>([]);
  const [batchRemovingStops, setBatchRemovingStops] = useState(false);

  // Clear stop selection when switching routes
  const setSelectedRouteIdAndClear = useCallback((id: string | null) => {
    setSelectedRouteId(id);
    setSelectedStopIds([]);
  }, []);

  const [createForm, setCreateForm] = useState({
    name: '',
    date: new Date().toISOString().slice(0, 10),
    driverId: '',
    vehicleId: '',
    depotId: '',
  });

  const [settingsForm, setSettingsForm] = useState({
    date: '',
    driverId: '',
    vehicleId: '',
    depotId: '',
  });

  // Memos
  const selectedRoute = useMemo(
    () => routes.find((route) => route.id === selectedRouteId) ?? null,
    [routes, selectedRouteId],
  );

  const selectedDepot = useMemo(() => {
    if (!selectedRoute?.depotId) return null;
    return depots.find((depot) => depot.id === selectedRoute.depotId) ?? null;
  }, [depots, selectedRoute?.depotId]);

  const selectedRouteStops = useMemo(
    () => [...(selectedRoute?.stops ?? [])].sort((a, b) => (a.stopOrder ?? 0) - (b.stopOrder ?? 0)),
    [selectedRoute],
  );

  const waitingMap = useMemo(() => {
    const map = new Map<string, DeliveryOption>();
    waitingDeliveries.forEach((item) => map.set(item.id, item));
    return map;
  }, [waitingDeliveries]);

  const driverNameById = useMemo(() => {
    const map = new Map<string, string>();
    drivers.forEach((driver) => map.set(driver.id, driver.name));
    return map;
  }, [drivers]);

  // Helper Functions
  const parseNumber = (value: unknown): number | null => {
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (typeof value === 'string' && value.trim()) {
      const parsed = Number(value);
      return Number.isFinite(parsed) ? parsed : null;
    }
    return null;
  };

  const extractStopWeight = (stop: any): number => {
    const candidates = [
      stop?.delivery?.order?.totalWeightKg,
      stop?.order?.totalWeightKg,
      stop?.delivery?.totalWeightKg,
      stop?.totalWeightKg,
    ];
    for (const candidate of candidates) {
      const parsed = parseNumber(candidate);
      if (parsed != null) return parsed;
    }
    return 0;
  };

  const fetchRouteWeights = async (items: RouteItem[]) => {
    if (items.length === 0) {
      setRouteWeightById({});
      return;
    }

    const results = await Promise.allSettled(
      items.map(async (route) => {
        const res = await api.get(`/api/admin/routes/${route.id}/full`);
        const rawStops = Array.isArray(res.data?.stops) ? res.data.stops : [];
        const totalWeight = rawStops.reduce((sum: number, stop: any) => sum + extractStopWeight(stop), 0);
        return { routeId: route.id, totalWeight };
      }),
    );

    const next: Record<string, number> = {};
    results.forEach((result) => {
      if (result.status !== 'fulfilled') return;
      next[result.value.routeId] = result.value.totalWeight;
    });
    setRouteWeightById(next);
  };

  const fetchRoutes = async () => {
    const params: Record<string, string> = {};
    if (routesDate) params.date = routesDate;
    const res = await api.get('/api/admin/routes', { params });
    const data = (Array.isArray(res.data) ? res.data : []).filter((route: RouteItem) => route.status === 'DRAFT');
    if (!isMounted.current) return;
    setRoutes(data);
    void fetchRouteWeights(data);
    if (!selectedRouteId && data.length > 0) {
      setSelectedRouteId(data[0].id);
    } else if (selectedRouteId && !data.some((r: RouteItem) => r.id === selectedRouteId)) {
      setSelectedRouteId(data.length > 0 ? data[0].id : null);
    }
  };

  const fetchAvailable = useCallback(async (date: string) => {
    if (!date) return;
    try {
      const [driversRes, vehiclesRes] = await Promise.all([
        api.get('/api/admin/fleet/drivers/available', { params: { date, startTime: '08:00', endTime: '18:00' } }),
        api.get('/api/admin/vehicles/available', { params: { date, startTime: '08:00', endTime: '18:00' } }),
      ]);
      if (!isMounted.current) return;
      setAvailableDrivers(Array.isArray(driversRes.data) ? driversRes.data : []);
      setAvailableVehicles(Array.isArray(vehiclesRes.data) ? vehiclesRes.data : []);
    } catch {
      // fall back to full lists silently
    }
  }, []);

  const fetchMeta = async () => {
    const [driversRes, vehiclesRes, waitingRes, depotsRes] = await Promise.all([
      api.get('/api/admin/fleet/drivers'),
      api.get('/api/admin/vehicles'),
      api.get('/api/admin/deliveries', { params: { status: 'UNSCHEDULED', size: 1000 } }),
      api.get('/api/v1/depots/active').catch(() => ({ data: [] })),
    ]);

    if (!isMounted.current) return;
    setDrivers(Array.isArray(driversRes.data) ? driversRes.data : []);
    setVehicles(Array.isArray(vehiclesRes.data) ? vehiclesRes.data : []);
    setDepots(Array.isArray(depotsRes.data) ? depotsRes.data : []);

    const raw = waitingRes.data?.content ?? waitingRes.data;
    const deliveries: DeliveryOption[] = Array.isArray(raw)
      ? raw
          .map((item: any) => {
            const id =
              item?.id ??
              item?.deliveryId ??
              item?.delivery?.id ??
              item?.order?.id ??
              item?.orderId ??
              item?.reference ??
              item?.externalId ??
              item?.delivery?.order?.reference ??
              item?.delivery?.order?.name ??
              item?.order?.reference ??
              item?.order?.name ??
              item?.delivery?.reference ??
              item?.delivery?.externalId;
            if (!id) return null;
            const clientName =
              item?.clientName ?? item?.delivery?.clientName ?? item?.order?.clientName ?? item?.recipientName ?? '';
            const dropoffLat =
              typeof item?.dropoffLat === 'number'
                ? item.dropoffLat
                : typeof item?.delivery?.dropoffLat === 'number'
                ? item.delivery.dropoffLat
                : typeof item?.order?.dropoffLat === 'number'
                ? item.order.dropoffLat
                : undefined;
            const dropoffLng =
              typeof item?.dropoffLng === 'number'
                ? item.dropoffLng
                : typeof item?.delivery?.dropoffLng === 'number'
                ? item.delivery.dropoffLng
                : typeof item?.order?.dropoffLng === 'number'
                ? item.order.dropoffLng
                : undefined;

            const erpOrderId = item?.erpOrderId ?? item?.delivery?.order?.erpOrderId ?? item?.order?.erpOrderId ?? '';
            const orderRef = item?.orderRef ?? item?.delivery?.order?.reference ?? item?.order?.reference ?? item?.order?.name ?? '';

            return {
              id: String(id),
              erpOrderId,
              orderRef,
              clientName,
              dropoffAddress:
                item?.dropoffAddress ?? item?.delivery?.dropoffAddress ?? item?.order?.dropoffAddress,
              dropoffCity: item?.dropoffCity ?? item?.delivery?.dropoffCity ?? item?.order?.dropoffCity,
              dropoffPostalCode:
                item?.dropoffPostalCode ?? item?.delivery?.dropoffPostalCode ?? item?.order?.dropoffPostalCode,
              dropoffCountryCode:
                item?.dropoffCountryCode ?? item?.delivery?.dropoffCountryCode ?? item?.order?.dropoffCountryCode,
              dropoffLat,
              dropoffLng,
              dropoffPinned:
                typeof item?.dropoffPinned === 'boolean'
                  ? item.dropoffPinned
                  : typeof item?.delivery?.dropoffPinned === 'boolean'
                  ? item.delivery.dropoffPinned
                  : undefined,
              totalWeightKg:
                parseNumber(item?.totalWeightKg) ??
                parseNumber(item?.delivery?.totalWeightKg) ??
                parseNumber(item?.order?.totalWeightKg) ??
                parseNumber(item?.delivery?.order?.totalWeightKg) ??
                0,
              totalQuantity: item?.totalQuantity ?? 0,
              itemsSummary: item?.itemsSummary ?? '',
              items: item?.items ?? [],
              createdAt: item?.createdAt,
              scheduledAt: item?.scheduledAt ?? item?.delivery?.scheduledAt ?? item?.order?.scheduledAt,
              status: item?.status ?? item?.delivery?.status ?? item?.order?.status ?? '',
              warehouseCode: item?.warehouseCode ?? item?.order?.warehouseCode ?? null,
              sourceDepotId: item?.sourceDepotId ?? item?.delivery?.sourceDepotId ?? item?.order?.sourceDepotId ?? null,
            } as DeliveryOption;
          })
          .filter((item: DeliveryOption | null): item is DeliveryOption => item !== null)
      : [];
    setWaitingDeliveries(deliveries);
  };

  const refreshAll = async (silent = false) => {
    if (!silent && isMounted.current) setLoading(true);
    try {
      await Promise.all([fetchRoutes(), fetchMeta()]);
    } catch {
      showErrorToast(null, t.routeBuilderPage.toastLoadFailed);
    } finally {
      if (!silent && isMounted.current) setLoading(false);
    }
  };

  useEffect(() => {
    void refreshAll();
  }, []);

  // Fetch available drivers/vehicles whenever the create modal is open and the date changes
  useEffect(() => {
    if (createOpen && createForm.date) void fetchAvailable(createForm.date);
  }, [createOpen, createForm.date, fetchAvailable]);

  // Re-fetch routes when the date filter changes (skip the initial mount —
  // the effect above already handles that)
  const didMountRef = useRef(false);
  useEffect(() => {
    if (!didMountRef.current) {
      didMountRef.current = true;
      return;
    }
    void fetchRoutes();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routesDate]);

  useEffect(() => {
    setSuggestion(null);
    setShowSuggestionTrajet(true);
    if (selectedRoute?.plannedStartTime) {
      setOptimizationStartTime(toShortTime(selectedRoute.plannedStartTime));
    } else {
      setOptimizationStartTime('08:00');
    }
  }, [selectedRouteId, selectedRoute?.plannedStartTime]);

  useEffect(() => {
    const next: Record<string, StopWindowDraft> = {};
    selectedRouteStops.forEach((stop) => {
      // PICKUP stops are system-reconciled — they carry no time window.
      if (stop.stopType === 'PICKUP') return;
      next[stop.id] = {
        startTime: toShortTime(stop.startTimeWindow) || toShortTime(selectedRoute?.plannedStartTime) || '08:00',
        endTime: toShortTime(stop.endTimeWindow) || toShortTime(selectedRoute?.plannedEndTime) || '18:00',
        buffer: String(stop.bufferMinutes ?? 30),
      };
    });
    setStopWindows(next);
  }, [selectedRoute?.id, selectedRoute?.plannedEndTime, selectedRoute?.plannedStartTime, selectedRouteStops]);

  useEffect(() => {
    if (!selectedRoute) {
      setSettingsForm({ date: '', driverId: '', vehicleId: '', depotId: '' });
      return;
    }
    setSettingsForm({
      date: selectedRoute.date,
      driverId: selectedRoute.driverId ?? '',
      vehicleId: selectedRoute.vehicleId ?? '',
      depotId: selectedRoute.depotId ?? '',
    });
  }, [selectedRoute]);

  // Re-detect the zone whenever the selected route OR its stops change.
  // The backend computes detectedZoneLabel from the stops' addresses/coordinates,
  // so adding/removing/moving a stop must trigger a refetch.
  const stopsSignature = useMemo(
    () => (selectedRoute?.stops ?? [])
      .map(s => `${s.deliveryId ?? s.id}:${s.stopOrder ?? ''}`)
      .join('|'),
    [selectedRoute?.stops],
  );

  useEffect(() => {
    let active = true;
    const loadRouteZone = async () => {
      if (!selectedRouteId) {
        if (active) setSelectedRouteZoneLabel('');
        return;
      }
      try {
        const res = await api.get(`/api/admin/routes/${selectedRouteId}/full`);
        if (!active) return;
        const data = res.data as any;
        setSelectedRouteZoneLabel(data?.detectedZoneLabel ?? data?.city ?? '');
      } catch {
        if (active) setSelectedRouteZoneLabel('');
      }
    };
    void loadRouteZone();
    return () => {
      active = false;
    };
  }, [selectedRouteId, stopsSignature]);

  const isVehicleBusy = (vehicle?: VehicleItem) => {
    if (!vehicle) return false;
    return Boolean((vehicle as any).assigned);
  };

  // A driver is busy if they're already assigned to another route in today's builder list.
  const isDriverBusy = (driver?: Driver, excludeRouteId?: string) => {
    if (!driver) return false;
    return routes.some((r) => r.driverId === driver.id && r.id !== excludeRouteId);
  };

  const appendStopsToRoute = (routeId: string, newStops: RouteStop[]) => {
    setRoutes((prev) => prev.map((route) => {
      if (route.id !== routeId) return route;
      return {
        ...route,
        stops: [...(route.stops ?? []), ...newStops],
      };
    }));
  };

  const removeFromUnscheduled = (ids: string[]) => {
    if (ids.length === 0) return;
    setWaitingDeliveries((prev) => prev.filter((delivery) => !ids.includes(delivery.id)));
  };

  const createRoute = async (): Promise<void> => {
    if (!createForm.name.trim()) { showErrorToast(null, t.routeBuilderPage.toastRouteNameRequired); return; }
    if (!createForm.date) { showErrorToast(null, t.routeBuilderPage.toastDateRequired); return; }
    if (!createForm.driverId) { showErrorToast(null, t.routeBuilderPage.toastDriverRequired); return; }
    if (!createForm.depotId) { showErrorToast(null, t.routeBuilderPage.toastDepotRequired); return; }

    if (createForm.vehicleId) {
      const selectedVehicle = vehicles.find((vehicle) => vehicle.id === createForm.vehicleId);
      if (selectedVehicle && isVehicleBusy(selectedVehicle)) {
        showErrorToast('already assigned'); return;
      }
    }

    try {
      setCreating(true);
      const res = await api.post('/api/admin/routes', {
        name: createForm.name.trim(),
        date: createForm.date,
        driverId: createForm.driverId,
        vehicleId: createForm.vehicleId || null,
        depotId: createForm.depotId,
        plannedStartTime: '08:00',
        plannedEndTime: '18:00',
      });

      setCreateOpen(false);
      showSuccessToast(t.routeBuilderPage.toastRouteCreated);
      const createdId = res.data?.id as string | undefined;
      setCreateForm((prev) => ({ ...prev, name: '' }));
      await refreshAll(true);
      if (createdId) setSelectedRouteId(createdId);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    } finally {
      setCreating(false);
    }
  };

  const assignDeliveryToRoute = async (routeId: string, deliveryId: string) => {
    const delivery = waitingMap.get(deliveryId);
    if (!delivery) return;

    const pinned = delivery.dropoffPinned !== false
      && typeof delivery.dropoffLat === 'number'
      && typeof delivery.dropoffLng === 'number';

    if (!pinned) {
      const orderLabel = delivery.orderRef || delivery.erpOrderId || deliveryId.slice(0, 8).toUpperCase();
      throw new Error(t.routeBuilderPage.toastOrderNotPinned.replace('{order}', orderLabel));
    }

    const route = routes.find((item) => item.id === routeId);

    // ── Duplicate guard ───────────────────────────────────────────────────────
    // Prevent the same delivery from being added twice to the same route.
    const alreadyInRoute = (route?.stops ?? []).some(
      (stop) => stop.deliveryId === deliveryId,
    );
    if (alreadyInRoute) {
      const orderLabel = delivery.orderRef || delivery.erpOrderId || deliveryId.slice(0, 8).toUpperCase();
      throw new Error(`La commande ${orderLabel} est déjà dans cette tournée.`);
    }
    // ─────────────────────────────────────────────────────────────────────────

    const orderedStops = [...(route?.stops ?? [])].sort((a, b) => (a.stopOrder ?? 0) - (b.stopOrder ?? 0));
    const lastStop = orderedStops.at(-1);
    const routeStart = toShortTime(route?.plannedStartTime) || '08:00';
    const routeEnd = toShortTime(route?.plannedEndTime) || '18:00';
    const lastStopEnd = toShortTime(lastStop?.endTimeWindow) || '';
    const defaultStart = lastStopEnd || routeStart;
    const defaultEnd = routeEnd > defaultStart ? routeEnd : defaultStart;

    await api.post(`/api/admin/routes/${routeId}/stops`, {
      deliveryId,
      startTimeWindow: defaultStart,
      endTimeWindow: defaultEnd,
      bufferMinutes: 30,
    });
  };

  const assignSelectedToActiveRoute = async (overrideRouteId?: string): Promise<void> => {
    const routeIdToUse = overrideRouteId ?? selectedRouteId;
    if (!routeIdToUse) { showErrorToast(null, t.routeBuilderPage.toastSelectRouteFirst); return; }
    if (selectedOrderIds.length === 0) { showErrorToast(null, t.routeBuilderPage.toastNoOrderSelected); return; }

    // Hard-block: a delivery whose ERP warehouse has no synced depot cannot be routed.
    const mappedIds = selectedOrderIds.filter((id) => {
      const d = waitingMap.get(id);
      return !d || !(d.warehouseCode && !d.sourceDepotId);
    });
    if (mappedIds.length === 0) { showErrorToast(null, t.routeBuilderPage.unmappedDepotChip); return; }

    try {
      setBatchAssigning(true);
      const assignedIds: string[] = [];
      const targetStops = routes.find((r) => r.id === routeIdToUse)?.stops ?? [];
      let nextStopOrder = targetStops.length + 1;

      for (const deliveryId of mappedIds) {
        try {
          await assignDeliveryToRoute(routeIdToUse, deliveryId);
          const delivery = waitingMap.get(deliveryId);
          if (delivery) {
            appendStopsToRoute(routeIdToUse, [{
              id: `pending-${deliveryId}`,
              deliveryId,
              stopOrder: nextStopOrder,
              dropoffLat: delivery.dropoffLat,
              dropoffLng: delivery.dropoffLng,
            }]);
            nextStopOrder += 1;
          }
          assignedIds.push(deliveryId);
        } catch (err: any) {
          showErrorToast(err?.response?.data?.message ?? err?.message);
        }
      }
      setSelectedOrderIds((prev) => prev.filter((id) => !assignedIds.includes(id)));
      removeFromUnscheduled(assignedIds);
      if (assignedIds.length > 0) {
        if (assignedIds.length === 1) {
          const d = waitingMap.get(assignedIds[0]);
          const orderLabel = d?.orderRef || d?.erpOrderId || assignedIds[0].slice(0, 8).toUpperCase();
          showSuccessToast(t.routeBuilderPage.toastOrderAssigned.replace('{order}', orderLabel));
        } else {
          showSuccessToast(t.routeBuilderPage.toastOrdersAssigned.replace('{count}', String(assignedIds.length)));
        }
      }
      await refreshAll(true);
      await api.post(`/api/admin/routes/${routeIdToUse}/recalculate`).catch(() => undefined);
    } finally {
      setBatchAssigning(false);
    }
  };

  const reorderSelectedRouteStops = async (fromIndex: number, toIndex: number) => {
    if (!selectedRoute || selectedRoute.status !== 'DRAFT') return;
    const items = [...selectedRouteStops];
    const [moved] = items.splice(fromIndex, 1);
    items.splice(toIndex, 0, moved);

    // Inline precedence guard (mirrors the server's assertPickupPrecedence): a DELIVERY stop
    // sourced from a non-home depot may not be ordered before that depot's PICKUP stop.
    if (!isPickupPrecedenceValid(items)) {
      showErrorToast(null, t.routeBuilderPage.precedenceViolation);
      return; // reject the drop; refreshAll() is skipped so the UI reverts to server order
    }

    const stopIds = items.map((item) => item.id);
    await api.put(`/api/admin/routes/${selectedRoute.id}/stops/reorder`, { stopIds });
    await api.post(`/api/admin/routes/${selectedRoute.id}/recalculate`).catch(() => undefined);
    await refreshAll(true);
  };

  const optimizeRouteOrder = async () => {
    if (!selectedRoute || selectedRouteStops.length < 2) return;
    try {
      setOptimizing(true);
      const res = await api.post(`/api/admin/routes/${selectedRoute.id}/optimize`);
      setSuggestion(res.data);
      showSuccessToast(t.routeBuilderPage.toastOsrmSuggested);
    } catch {
      showErrorToast(null, t.routeBuilderPage.toastOsrmFailed);
    } finally {
      setOptimizing(false);
    }
  };

  // Lock or unlock a single route (PATCH /api/admin/routes/{id}/lock).
  // Optimistic local update + reconcile from server.
  const lockRoute = async (routeId: string, locked: boolean) => {
    setLockingRouteId(routeId);
    setRoutes((prev) => prev.map((r) => (r.id === routeId ? { ...r, locked } : r)));
    try {
      await api.patch(`/api/admin/routes/${routeId}/lock`, { locked });
      showSuccessToast(locked ? t.routeBuilderPage.toastRouteLocked : t.routeBuilderPage.toastRouteUnlocked);
    } catch {
      // Revert on error
      setRoutes((prev) => prev.map((r) => (r.id === routeId ? { ...r, locked: !locked } : r)));
      showErrorToast(null, locked ? t.routeBuilderPage.toastLockFailed : t.routeBuilderPage.toastUnlockFailed);
    } finally {
      setLockingRouteId(null);
    }
  };

  // Batch optimize: run /optimize for each selected route in parallel,
  // skipping locked routes. Used by "Optimiser la sélection" in the sidebar.
  const batchOptimizeRoutes = async (routeIds: string[]) => {
    const candidates = routes.filter(
      (r) => routeIds.includes(r.id) && !r.locked && (r.stops?.length ?? 0) >= 2,
    );
    if (candidates.length === 0) {
      showSuccessToast(t.routeBuilderPage.toastNoEligibleRoutes);
      return;
    }
    setBatchOptimizing(true);
    try {
      const settled = await Promise.allSettled(
        candidates.map((r) => api.post(`/api/admin/routes/${r.id}/optimize`)),
      );
      const ok = settled.filter((s) => s.status === 'fulfilled').length;
      const ko = settled.length - ok;
      if (ok > 0 && ko === 0) {
        showSuccessToast(t.routeBuilderPage.toastBatchOptimizeOk.replace('{count}', String(ok)).replace('{plural}', ok > 1 ? 's' : ''));
      } else if (ok > 0 && ko > 0) {
        showSuccessToast(t.routeBuilderPage.toastBatchOptimizePartial.replace('{ok}', String(ok)).replace('{ko}', String(ko)).replace('{plural}', ok > 1 ? 's' : ''));
      } else {
        showErrorToast(null, t.routeBuilderPage.toastBatchOptimizeFailed);
      }
      await refreshAll(true);
    } finally {
      setBatchOptimizing(false);
    }
  };

  const toggleBatchSelection = (routeId: string) => {
    setBatchSelectedRouteIds((prev) =>
      prev.includes(routeId) ? prev.filter((id) => id !== routeId) : [...prev, routeId],
    );
  };

  const clearBatchSelection = () => setBatchSelectedRouteIds([]);

  const applyBackendOptimization = async () => {
    if (!selectedRoute || !suggestionEtaRows.length) return;
    try {
      setOptimizing(true);

      // Derive plannedEndTime so the backend's
      // `plannedStartTime < plannedEndTime` invariant always holds.
      // Take the latest of: existing plannedEndTime, last suggested stop end + 30min buffer.
      const lastRow = suggestionEtaRows[suggestionEtaRows.length - 1];
      const computedEndTime = lastRow?.suggestedEnd
        ? addMinutesToTime(lastRow.suggestedEnd, 30)
        : null;
      const existingEndTime = toShortTime(selectedRoute.plannedEndTime);
      const plannedEndTime =
        computedEndTime && (!existingEndTime || computedEndTime > existingEndTime)
          ? computedEndTime
          : existingEndTime || addMinutesToTime(optimizationStartTime, 60);

      const payload = {
        name: selectedRoute.name,
        driverId: selectedRoute.driverId,
        vehicleId: selectedRoute.vehicleId || null,
        date: selectedRoute.date,
        plannedStartTime: optimizationStartTime,
        plannedEndTime,
        depotId: selectedRoute.depotId,
        deliveryIds: suggestionEtaRows.map((s) => {
          const stop = selectedRouteStops.find(rs => rs.id === s.key);
          return stop?.deliveryId || '';
        }).filter(Boolean),
        stopConfigs: suggestionEtaRows.map((row) => {
          const stop = selectedRouteStops.find(rs => rs.id === row.key);
          return {
            deliveryId: stop?.deliveryId || '',
            startTimeWindow: row.suggestedStart,
            endTimeWindow: row.suggestedEnd,
            bufferMinutes: 0,
          };
        }).filter(c => c.deliveryId),
      };

      await api.put(`/api/admin/routes/${selectedRoute.id}`, payload);
      await api.post(`/api/admin/routes/${selectedRoute.id}/recalculate`).catch(() => undefined);

      showSuccessToast(t.routeBuilderPage.toastOsrmApplied);
      setSuggestion(null);
      setSelectedStopIds([]);
      await refreshAll(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    } finally {
      setOptimizing(false);
    }
  };

  const removeStopFromRoute = async (stopId: string) => {
    if (!selectedRoute || selectedRoute.status !== 'DRAFT') return;
    try {
      setRemovingStopId(stopId);
      await api.delete(`/api/admin/routes/${selectedRoute.id}/stops/${stopId}`);
      await api.post(`/api/admin/routes/${selectedRoute.id}/recalculate`).catch(() => undefined);
      showSuccessToast(t.routeBuilderPage.toastStopRemoved);
      await refreshAll(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    } finally {
      setRemovingStopId(null);
    }
  };

  const batchRemoveStops = async (stopIds: string[]) => {
    if (!selectedRoute || selectedRoute.status !== 'DRAFT' || stopIds.length === 0) return;
    setBatchRemovingStops(true);
    let removed = 0;
    for (const stopId of stopIds) {
      try {
        await api.delete(`/api/admin/routes/${selectedRoute.id}/stops/${stopId}`);
        removed += 1;
      } catch {
        // continue — remove as many as possible
      }
    }
    if (removed > 0) {
      await api.post(`/api/admin/routes/${selectedRoute.id}/recalculate`).catch(() => undefined);
      showSuccessToast(removed === 1 ? t.routeBuilderPage.toastStopRemoved : t.routeBuilderPage.toastStopsRemoved.replace('{count}', String(removed)));
      await refreshAll(true);
    }
    setSelectedStopIds([]);
    setBatchRemovingStops(false);
  };

  const derivePlannedBounds = (
    route: RouteItem,
    orderedStops: RouteStop[],
    configs: Record<string, StopWindowDraft>,
  ) => {
    const fallbackStart = toShortTime(route.plannedStartTime) || '08:00';
    const fallbackEnd = toShortTime(route.plannedEndTime) || '18:00';
    if (orderedStops.length === 0) {
      return { plannedStartTime: fallbackStart, plannedEndTime: fallbackEnd };
    }
    const first = configs[orderedStops[0].id];
    const last = configs[orderedStops[orderedStops.length - 1].id];
    const plannedStartTime = first?.startTime || fallbackStart;
    const plannedEndTime = last?.endTime || fallbackEnd || plannedStartTime;
    return { plannedStartTime, plannedEndTime };
  };

  const saveStopWindows = async () => {
    if (!selectedRoute || selectedRoute.status !== 'DRAFT') return;

    // Pickups are reconciled server-side; only delivery stops carry windows + ids.
    const ordered = selectedRouteStops.filter((s) => s.stopType !== 'PICKUP');
    let prevEnd = toShortTime(selectedRoute.plannedStartTime) || '08:00';
    for (const stop of ordered) {
      const w = stopWindows[stop.id];
      if (!w || !w.startTime || !w.endTime) {
        showErrorToast(null, t.routeBuilderPage.toastTimeWindowRequired);
        return;
      }
      if (w.endTime <= w.startTime) {
        showErrorToast(null, t.routeBuilderPage.toastTimeWindowEndBeforeStart);
        return;
      }
      if (w.startTime < prevEnd) {
        showErrorToast(null, t.routeBuilderPage.toastTimeWindowChronoError);
        return;
      }
      prevEnd = w.endTime;
    }

    const timeBounds = derivePlannedBounds(selectedRoute, ordered, stopWindows);

    const payload = {
      name: selectedRoute.name,
      driverId: selectedRoute.driverId,
      vehicleId: selectedRoute.vehicleId || null,
      date: selectedRoute.date,
      plannedStartTime: timeBounds.plannedStartTime,
      plannedEndTime: timeBounds.plannedEndTime || prevEnd,
      depotId: selectedRoute.depotId,
      deliveryIds: ordered.map((s) => s.deliveryId),
      stopConfigs: ordered.map((s) => ({
        deliveryId: s.deliveryId,
        startTimeWindow: stopWindows[s.id]?.startTime,
        endTimeWindow: stopWindows[s.id]?.endTime,
        bufferMinutes: parseInt(stopWindows[s.id]?.buffer || '30', 10),
      })),
    };

    try {
      setSavingWindows(true);
      await api.put(`/api/admin/routes/${selectedRoute.id}`, payload);
      await api.post(`/api/admin/routes/${selectedRoute.id}/recalculate`).catch(() => undefined);
      showSuccessToast(t.routeBuilderPage.toastTimeWindowsSaved);
      await refreshAll(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    } finally {
      setSavingWindows(false);
    }
  };

  const saveRouteSettings = async (): Promise<void> => {
    if (!selectedRoute || selectedRoute.status !== 'DRAFT') return;
    if (!settingsForm.date) { showErrorToast(null, t.routeBuilderPage.toastSettingsDateRequired); return; }
    if (!settingsForm.driverId) { showErrorToast(null, t.routeBuilderPage.toastDriverRequired); return; }
    if (!settingsForm.depotId) { showErrorToast(null, t.routeBuilderPage.toastDepotRequired); return; }

    if (settingsForm.vehicleId && settingsForm.vehicleId !== selectedRoute.vehicleId) {
      const chosenVehicle = vehicles.find((vehicle) => vehicle.id === settingsForm.vehicleId);
      if (chosenVehicle && isVehicleBusy(chosenVehicle)) {
        showErrorToast('already assigned'); return;
      }
    }

    // Pickups are reconciled server-side; only delivery stops carry windows + ids.
    const ordered = selectedRouteStops.filter((s) => s.stopType !== 'PICKUP');
    const timeBounds = derivePlannedBounds(selectedRoute, ordered, stopWindows);

    const payload = {
      name: selectedRoute.name,
      driverId: settingsForm.driverId,
      vehicleId: settingsForm.vehicleId || null,
      date: settingsForm.date,
      plannedStartTime: timeBounds.plannedStartTime,
      plannedEndTime: timeBounds.plannedEndTime,
      depotId: settingsForm.depotId,
      deliveryIds: ordered.map((s) => s.deliveryId),
      stopConfigs: ordered.map((s) => ({
        deliveryId: s.deliveryId,
        startTimeWindow: stopWindows[s.id]?.startTime ?? toShortTime(s.startTimeWindow) ?? '08:00',
        endTimeWindow: stopWindows[s.id]?.endTime ?? toShortTime(s.endTimeWindow) ?? '18:00',
        bufferMinutes: parseInt(stopWindows[s.id]?.buffer || String(s.bufferMinutes ?? 30), 10),
      })),
    };

    try {
      setSavingSettings(true);
      await api.put(`/api/admin/routes/${selectedRoute.id}`, payload);
      await api.post(`/api/admin/routes/${selectedRoute.id}/recalculate`).catch(() => undefined);
      showSuccessToast(t.routeBuilderPage.toastSettingsSaved);
      setSettingsOpen(false);
      await refreshAll(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    } finally {
      setSavingSettings(false);
    }
  };

  const deleteDraftRoute = async (routeId: string) => {
    const route = routes.find((item) => item.id === routeId);
    if (!route || route.status !== 'DRAFT') return;
    setConfirmDeleteRouteId(routeId);
  };

  const confirmDeleteDraftRoute = async () => {
    if (!confirmDeleteRouteId) return;
    try {
      setDeletingRouteId(confirmDeleteRouteId);
      await api.delete(`/api/admin/routes/${confirmDeleteRouteId}`);
      showSuccessToast(t.routeBuilderPage.toastItineraryClear);
      setConfirmDeleteRouteId(null);
      await refreshAll(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    } finally {
      setDeletingRouteId(null);
    }
  };

  const validateRoute = async (routeId: string) => {
    try {
      setValidatingRouteId(routeId);
      await api.put(`/api/admin/routes/${routeId}/validate`);
      showSuccessToast(t.routeBuilderPage.toastRouteValidated);
      setConfirmValidateRouteId(null);
      await refreshAll(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    } finally {
      setValidatingRouteId(null);
    }
  };

  // Public batch assign — used by map drag-and-drop
  const assignDeliveriesToRoute = async (deliveryIds: string[], routeId: string): Promise<void> => {
    if (deliveryIds.length === 0) return;
    const route = routes.find((r) => r.id === routeId);
    if (!route) { showErrorToast(null, t.routeBuilderPage.toastRouteNotFound); return; }

    let successCount = 0;
    const assignedIds: string[] = [];
    let nextStopOrder = (route.stops?.length ?? 0) + 1;

    for (const id of deliveryIds) {
      try {
        await assignDeliveryToRoute(routeId, id);
        const delivery = waitingMap.get(id);
        if (delivery) {
          appendStopsToRoute(routeId, [{
            id: `pending-${id}`,
            deliveryId: id,
            stopOrder: nextStopOrder,
            dropoffLat: delivery.dropoffLat,
            dropoffLng: delivery.dropoffLng,
          }]);
          nextStopOrder += 1;
        }
        successCount += 1;
        assignedIds.push(id);
      } catch (err: any) {
        showErrorToast(err?.response?.data?.message ?? err?.message);
      }
    }

    if (successCount > 0) {
      if (successCount === 1) {
        const d = waitingMap.get(assignedIds[0]);
        const orderLabel = d?.orderRef || d?.erpOrderId || assignedIds[0].slice(0, 8).toUpperCase();
        showSuccessToast(t.routeBuilderPage.toastOrderAssigned.replace('{order}', orderLabel));
      } else {
        showSuccessToast(t.routeBuilderPage.toastOrdersAssigned.replace('{count}', String(successCount)));
      }
    }
    setSelectedOrderIds((prev) => prev.filter((id) => !deliveryIds.includes(id)));
    removeFromUnscheduled(assignedIds);
    await refreshAll(true);
    await api.post(`/api/admin/routes/${routeId}/recalculate`).catch(() => undefined);
  };

  // ── Drop: order row → route sidebar card ──────────────────────────────────
  const dropOrderOnRoute = async (deliveryId: string, routeId: string) => {
    const selectedSet = new Set(selectedOrderIds);
    const draggedIsSelected = selectedSet.has(deliveryId);
    const deliveryIdsToAssign = draggedIsSelected && selectedOrderIds.length > 1
      ? [...selectedOrderIds]
      : [deliveryId];

    try {
      let successCount = 0;
      const assignedIds: string[] = [];
      let nextStopOrder = (selectedRouteStops.length ?? 0) + 1;

      for (const id of deliveryIdsToAssign) {
        try {
          await assignDeliveryToRoute(routeId, id);
          const delivery = waitingMap.get(id);
          if (delivery) {
            appendStopsToRoute(routeId, [{
              id: `pending-${id}`,
              deliveryId: id,
              stopOrder: nextStopOrder,
              dropoffLat: delivery.dropoffLat,
              dropoffLng: delivery.dropoffLng,
            }]);
            nextStopOrder += 1;
          }
          successCount += 1;
          assignedIds.push(id);
        } catch (err: any) {
          showErrorToast(err?.response?.data?.message ?? err?.message);
        }
      }

      if (successCount > 0) {
        if (successCount === 1) {
          const d = waitingMap.get(assignedIds[0]);
          const orderLabel = d?.orderRef || d?.erpOrderId || assignedIds[0].slice(0, 8).toUpperCase();
          showSuccessToast(t.routeBuilderPage.toastOrderAssigned.replace('{order}', orderLabel));
        } else {
          showSuccessToast(t.routeBuilderPage.toastOrdersAssigned.replace('{count}', String(successCount)));
        }
      }
      setSelectedOrderIds((prev) => prev.filter((id) => !deliveryIdsToAssign.includes(id)));
      removeFromUnscheduled(assignedIds);
      await refreshAll(true);
      await api.post(`/api/admin/routes/${routeId}/recalculate`).catch(() => undefined);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message);
    }
  };

  // ── Optimistic reorder (same route) ──────────────────────────────────────
  const reorderRouteStopsOptimistic = async (fromIndex: number, toIndex: number) => {
    if (!selectedRoute || selectedRoute.status !== 'DRAFT') return;

    const snapshotRoutes = JSON.parse(JSON.stringify(routes)) as RouteItem[];

    // Compute new order and apply optimistically
    const orderedItems = [...selectedRouteStops];
    const [moved] = orderedItems.splice(fromIndex, 1);
    orderedItems.splice(toIndex, 0, moved);
    const stopIds = orderedItems.map((s) => s.id);

    setRoutes((prev) => prev.map((route) => {
      if (route.id !== selectedRouteId) return route;
      return { ...route, stops: orderedItems };
    }));

    try {
      await api.put(`/api/admin/routes/${selectedRoute.id}/stops/reorder`, { stopIds });
      await api.post(`/api/admin/routes/${selectedRoute.id}/recalculate`).catch(() => undefined);
      await refreshAll(true);
      setSelectedStopIds([]);
    } catch {
      setRoutes(snapshotRoutes);
      showErrorToast(null, t.routeBuilderPage.toastReorgFailed);
    }
  };

  // ── Optimistic cross-route stop transfer ─────────────────────────────────
  const transferStopOptimistic = async (stopId: string, sourceRouteId: string, targetRouteId: string) => {
    const snapshotRoutes = JSON.parse(JSON.stringify(routes)) as RouteItem[];

    setRoutes((prev) => prev.map((route) => {
      if (route.id !== sourceRouteId) return route;
      return { ...route, stops: (route.stops ?? []).filter((s) => s.id !== stopId) };
    }));

    try {
      await api.post('/api/admin/routes/transfer-stops', {
        sourceRouteId,
        targetRouteId,
        stopIds: [stopId],
      });
      showSuccessToast(t.routeBuilderPage.toastStopTransferred);
      await refreshAll(true);
    } catch (err: any) {
      setRoutes(snapshotRoutes);
      showErrorToast(null, t.routeBuilderPage.toastTransferFailed);
    }
  };

  // ── Unified @dnd-kit drag handlers ───────────────────────────────────────
  const handleDragStart = (event: DragStartEvent) => {
    setActiveDragId(String(event.active.id));
  };

  const handleDragEnd = async ({ active, over }: DragEndEvent) => {
    setActiveDragId(null);
    if (!over) return;

    const activeStr = String(active.id);
    const overStr = String(over.id);

    // Parse namespaced IDs
    // order:{deliveryId}              → activeType='order', activeItemId=deliveryId
    // stop:{stopId}:{routeId}         → activeType='stop',  activeItemId=stopId, activeRouteId=routeId
    // route-drop:{routeId}            → overType='route-drop', overItemId=routeId
    // stop:{stopId}:{routeId}         → overType='stop', overItemId=stopId, overRouteId=routeId
    // unscheduled-drop                → overType='unscheduled-drop'
    const [activeType, activeItemId, activeRouteId] = activeStr.split(':');
    const [overType, overItemId, overRouteId] = overStr.split(':');

    // 1. Order row → route drop zone (skip if manually dragged from map — manual handler already processed it)
    if (activeType === 'order' && overType === 'route-drop') {
      // Manual pin drag from map handles its own assignment + toast; only handle DND table drops
      // If drag source is not from orders table (e.g., from map), skip
      if (active.data?.current?.source !== 'orders-table') return;
      await dropOrderOnRoute(activeItemId, overItemId);
      return;
    }

    // 2. Stop → stop in same route (reorder)
    if (activeType === 'stop' && overType === 'stop' && activeRouteId === overRouteId) {
      const fromIndex = selectedRouteStops.findIndex((s) => s.id === activeItemId);
      const toIndex = selectedRouteStops.findIndex((s) => s.id === overItemId);
      if (fromIndex !== -1 && toIndex !== -1 && fromIndex !== toIndex) {
        await reorderRouteStopsOptimistic(fromIndex, toIndex);
      }
      return;
    }

    // 3. Stop → different route drop zone (cross-route transfer)
    if (activeType === 'stop' && overType === 'route-drop' && activeRouteId !== overItemId) {
      await transferStopOptimistic(activeItemId, activeRouteId, overItemId);
      return;
    }

    // Keep overRouteId used (satisfies lint)
    void overRouteId;
  };

  const computeChronologicalViolations = (
    departureTime: string,
    allStops: RouteStop[],
    configs: Record<string, StopWindowDraft>,
  ): Record<string, string | null> => {
    const violations: Record<string, string | null> = {};
    // PICKUP stops are system-managed (default 08:00–18:00 windows) — exclude them from the
    // delivery time-window chain so a depot pickup's end window never blocks a delivery.
    const stops = allStops.filter((s) => s.stopType !== 'PICKUP');
    for (let i = 0; i < stops.length; i++) {
      const stopId = stops[i].id;
      const cfg = configs[stopId];
      if (!cfg) {
        violations[stopId] = null;
        continue;
      }
      if (cfg.endTime <= cfg.startTime) {
        violations[stopId] = `End (${cfg.endTime}) must be after start (${cfg.startTime})`;
        continue;
      }
      if (i === 0) {
        violations[stopId] = cfg.startTime < departureTime
          ? `Stop #1 cannot start before departure (${departureTime})`
          : null;
        continue;
      }
      const prevCfg = configs[stops[i - 1].id];
      violations[stopId] = prevCfg && cfg.startTime < prevCfg.endTime
        ? `Must start after previous stop end (${prevCfg.endTime})`
        : null;
    }
    return violations;
  };

  // State calculations
  const selectedVehicle = vehicles.find((item) => item.id === selectedRoute?.vehicleId);
  const canModifyRoute = selectedRoute?.status === 'DRAFT' || selectedRoute?.status === 'VALIDATED' || selectedRoute?.status === 'IN_PROGRESS';
  const selectedRouteIsDraft = selectedRoute?.status === 'DRAFT';
  
  const selectedWeight = useMemo(() => {
    return selectedRouteStops.reduce((sum, stop) => {
      const del = waitingMap.get(stop.deliveryId);
      if (del?.totalWeightKg != null) return sum + del.totalWeightKg;
      return sum + extractStopWeight(stop);
    }, 0);
  }, [selectedRouteStops, waitingMap]);

  const vehicleCapacity = selectedVehicle?.payloadKg ?? 0;
  const overloadKg = Math.max(0, selectedWeight - vehicleCapacity);
  const payloadPercent = vehicleCapacity > 0 ? Math.round((selectedWeight / vehicleCapacity) * 100) : 0;
  
  const chronoViolations = useMemo(() => {
    return computeChronologicalViolations(
      toShortTime(selectedRoute?.plannedStartTime) || '08:00',
      selectedRouteStops,
      stopWindows,
    );
  }, [selectedRoute?.plannedStartTime, selectedRouteStops, stopWindows]);

  const hasChronoViolation = Object.values(chronoViolations).some(Boolean);
  
  const missingWindowCount = selectedRouteStops.filter((stop) => {
    if (stop.stopType === 'PICKUP') return false; // pickups are auto-created, not window-validated
    const cfg = stopWindows[stop.id];
    return !(cfg && cfg.startTime && cfg.endTime);
  }).length;

  const canValidate = missingWindowCount === 0 && !hasChronoViolation;

  const filteredDeliveries = useMemo(() => {
    let result = waitingDeliveries;

    // Text search
    if (deliverySearch.trim()) {
      const q = deliverySearch.toLowerCase();
      result = result.filter((d) =>
        (d.clientName ?? '').toLowerCase().includes(q) ||
        (d.dropoffAddress ?? '').toLowerCase().includes(q) ||
        (d.dropoffCity ?? '').toLowerCase().includes(q) ||
        (d.erpOrderId ?? '').toLowerCase().includes(q) ||
        (d.orderRef ?? '').toLowerCase().includes(q) ||
        (d.id ?? '').toLowerCase().includes(q),
      );
    }

    // Date quick view
    if (orderQuickView !== 'all') {
      const now = new Date();
      // Use local date (matches DeliveriesPage behaviour)
      const localDate = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().split('T')[0];

      if (orderQuickView === 'today') {
        result = result.filter((d) => {
          const dateToCheck = d.scheduledAt ?? d.createdAt;
          return !!dateToCheck && dateToCheck.slice(0, 10) === localDate;
        });
      } else if (orderQuickView === 'thisWeek') {
        // Build local-tz week boundaries using the same local‑date trick
        const localNow = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
        const localDay = localNow.getUTCDay();
        const mondayOffset = localDay === 0 ? -6 : 1 - localDay;
        const weekStart = new Date(Date.UTC(localNow.getUTCFullYear(), localNow.getUTCMonth(), localNow.getUTCDate() + mondayOffset));
        const weekEnd = new Date(weekStart);
        weekEnd.setUTCDate(weekStart.getUTCDate() + 6);
        const weekStartStr = weekStart.toISOString().slice(0, 10);
        const weekEndStr = weekEnd.toISOString().slice(0, 10);

        result = result.filter((d) => {
          const dateToCheck = d.scheduledAt ?? d.createdAt;
          if (!dateToCheck) return false;
          const dStr = dateToCheck.slice(0, 10);
          return dStr >= weekStartStr && dStr <= weekEndStr;
        });
      }
    }

    return result;
  }, [waitingDeliveries, deliverySearch, orderQuickView]);

  const suggestionEtaRows = useMemo(() => {
    if (!suggestion || !selectedRoute) return [];
    const stopById = new Map(selectedRouteStops.map((stop) => [stop.id, stop]));

    const routeStart = optimizationStartTime || '08:00';
    let [h, m] = routeStart.split(':').map(Number);
    const currentTime = new Date();
    currentTime.setHours(h, m, 0, 0);

    let previousWindowEnd = routeStart; // first stop opens at route departure

    return [...(suggestion.optimizedStops ?? [])]
      .sort((a, b) => (a.sequenceOrder ?? 0) - (b.sequenceOrder ?? 0))
      .map((etaStop) => {
        const routeStop = stopById.get(etaStop.stopId);
        const delivery = routeStop ? waitingMap.get(routeStop.deliveryId) : undefined;

        // Round drive time to nearest minute so display and ETA are consistent
        const driveSec = etaStop.driveDurationSeconds ?? 0;
        const driveMin = Math.round(driveSec / 60);
        currentTime.setMinutes(currentTime.getMinutes() + driveMin);

        const arrivalISO = currentTime.toISOString();
        const arrivalTimeStr = `${String(currentTime.getHours()).padStart(2, '0')}:${String(currentTime.getMinutes()).padStart(2, '0')}`;

        // Window start = when previous stop ends (driver is committed from that moment)
        // Window end   = ETA + 10 min (must complete within 10 min of physical arrival)
        const suggestedStart = previousWindowEnd;
        const suggestedEnd = addMinutesToTime(arrivalTimeStr, 10);
        previousWindowEnd = suggestedEnd;

        // Advance clock by service time before next leg
        currentTime.setMinutes(currentTime.getMinutes() + SERVICE_MINUTES);

        const isPickup = routeStop?.stopType === 'PICKUP';

        return {
          key: etaStop.stopId,
          sequenceOrder: etaStop.sequenceOrder,
          stopType: routeStop?.stopType,
          sourceDepotName: routeStop?.sourceDepotName ?? undefined,
          clientName: isPickup
            ? (routeStop?.sourceDepotName ?? '')
            : (delivery?.clientName?.trim() ? delivery.clientName : (routeStop?.deliveryId ? routeStop.deliveryId.slice(0, 8).toUpperCase() : 'Stop')),
          dropoffAddress: isPickup ? '' : (delivery?.dropoffAddress ?? ''),
          dropoffCity: isPickup ? '' : (delivery?.dropoffCity ?? ''),
          etaAt: arrivalISO,
          suggestedStart,
          suggestedEnd,
          driveDurationSeconds: etaStop.driveDurationSeconds,
          driveDistanceMeters: etaStop.driveDistanceMeters,
        };
      });
  }, [suggestion, selectedRoute, selectedRouteStops, waitingMap, optimizationStartTime]);

  return {
    // State
    loading,
    routes,
    drivers,
    availableDrivers,
    availableVehicles,
    vehicles,
    depots,
    waitingDeliveries,
    filteredDeliveries,
    selectedRouteId,
    selectedRoute,
    selectedDepot,
    selectedRouteStops,
    selectedOrderIds,
    stopWindows,
    createOpen,
    creating,
    batchAssigning,
    savingWindows,
    validatingRouteId,
    confirmValidateRouteId,
    removingStopId,
    deletingRouteId,
    settingsOpen,
    savingSettings,
    optimizing,
    suggestion,
    showRouteTrajet,
    showSuggestionTrajet,
    selectedRouteZoneLabel,
    routeWeightById,
    confirmDeleteRouteId,
    deliverySearch,
    createForm,
    settingsForm,
    
    // Memos/Derived
    waitingMap,
    driverNameById,
    selectedWeight,
    vehicleCapacity,
    overloadKg,
    payloadPercent,
    chronoViolations,
    hasChronoViolation,
    missingWindowCount,
    canValidate,
    suggestionEtaRows,
    selectedRouteIsDraft,
    canModifyRoute,
    
    // Setters
    setSelectedRouteId: setSelectedRouteIdAndClear,
    setSelectedOrderIds,
    setCreateOpen,
    setSettingsOpen,
    setSuggestion,
    setShowRouteTrajet,
    setShowSuggestionTrajet,
    setConfirmDeleteRouteId,
    setDeliverySearch,
    orderQuickView,
    setOrderQuickView,
    optimizationStartTime,
    setOptimizationStartTime,
    setCreateForm,
    setSettingsForm,
    setStopWindows,
    setConfirmValidateRouteId,
    
    // Handlers
    refreshAll,
    createRoute,
    assignSelectedToActiveRoute,
    assignDeliveriesToRoute,
    reorderSelectedRouteStops,
    optimizeRouteOrder,
    applyBackendOptimization,
    removeStopFromRoute,
    batchRemoveStops,
    selectedStopIds,
    setSelectedStopIds,
    batchRemovingStops,
    saveStopWindows,
    saveRouteSettings,
    confirmDeleteDraftRoute,
    deleteDraftRoute,
    validateRoute,
    activeDragId,
    handleDragStart,
    handleDragEnd,
    isVehicleBusy,
    isDriverBusy,
    removeFromUnscheduled,
    appendStopsToRoute,

    // Phase 2: date filter, multi-select, batch optimize, lock
    routesDate,
    setRoutesDate,
    batchSelectedRouteIds,
    toggleBatchSelection,
    clearBatchSelection,
    batchOptimizeRoutes,
    batchOptimizing,
    lockRoute,
    lockingRouteId,
  };
}

// ── Context Integration ───────────────────────────────────────────────────────

export type RouteBuilderContextType = ReturnType<typeof useRouteBuilder>;

const RouteBuilderContext = createContext<RouteBuilderContextType | null>(null);

export function RouteBuilderProvider({ children }: { children: ReactNode }) {
  const value = useRouteBuilder();
  return React.createElement(RouteBuilderContext.Provider, { value }, children);
}

export function useRouteBuilderContext() {
  const context = useContext(RouteBuilderContext);
  if (!context) {
    throw new Error('useRouteBuilderContext must be used within a RouteBuilderProvider');
  }
  return context;
}

