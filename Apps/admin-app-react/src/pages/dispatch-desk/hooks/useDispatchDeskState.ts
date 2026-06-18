import React, { createContext, useContext, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate as useRouter, useSearchParams } from 'react-router-dom';
import { api } from '@/lib/api';
import type { Driver, Delivery, Zone } from '@/types';
import { showSuccessToast, showErrorToast } from '@/lib/toast-service';
import { useGlobalFilters } from '@/lib/global-filters';
import { getCurrentUser, getCurrentRole, isReadOnlyRole } from '@/lib/auth';
import { usePageBreadcrumb } from '@/lib/breadcrumb';
import { useT } from '@/lib/LocaleContext';
import { useRealtimeEvent } from '@/components/RealtimeProvider';

import type { OpsException, OpsExceptionResponse, Period, ActionKind, DispatchTab, PendingAction, QueueRow } from '../types';
import type { ReassignTarget } from '@/components/overlays/ReassignDrawer';
import { REASSIGNABLE_STATUSES, REPLANNABLE_STATUSES, ASSIGNABLE_STATUSES } from '../constants';
import { formatMotif, formatComment } from '../formatters';
import { rowId, getWeekStart, getMonthStart, sortByRoute, sortQueue, type QueueSortMode } from '../utils';

export interface DispatchDeskContextProps {
  t: ReturnType<typeof useT>;
  isReadOnly: boolean;
  
  // Data State
  rows: OpsException[];
  drivers: Driver[];
  zones: Zone[];
  allDeliveries: Delivery[];
  loading: boolean;
  refreshing: boolean;
  allLoading: boolean;
  lastUpdated: Date | null;
  newSinceLoad: number;
  setNewSinceLoad: React.Dispatch<React.SetStateAction<number>>;
  
  // Filters State
  period: Period;
  setPeriod: React.Dispatch<React.SetStateAction<Period>>;
  customFrom: string;
  setCustomFrom: React.Dispatch<React.SetStateAction<string>>;
  customTo: string;
  setCustomTo: React.Dispatch<React.SetStateAction<string>>;
  search: string;
  setSearch: React.Dispatch<React.SetStateAction<string>>;
  driverId: string;
  setDriverId: React.Dispatch<React.SetStateAction<string>>;
  zoneFilter: string;
  setZoneFilter: React.Dispatch<React.SetStateAction<string>>;
  statusFilter: string;
  setStatusFilter: React.Dispatch<React.SetStateAction<string>>;
  routeFilter: string;
  setRouteFilter: React.Dispatch<React.SetStateAction<string>>;
  queueSort: QueueSortMode;
  setQueueSort: React.Dispatch<React.SetStateAction<QueueSortMode>>;
  clearFilters: () => void;
  
  // UI State
  dispatchTab: DispatchTab;
  setDispatchTab: React.Dispatch<React.SetStateAction<DispatchTab>>;
  mobileTab: 'filters' | 'list';
  setMobileTab: React.Dispatch<React.SetStateAction<'filters' | 'list'>>;
  filtersOpen: boolean;
  setFiltersOpen: React.Dispatch<React.SetStateAction<boolean>>;
  expandedActionId: string | null;
  setExpandedActionId: React.Dispatch<React.SetStateAction<string | null>>;
  selectedQueueId: string | null;
  setSelectedQueueId: React.Dispatch<React.SetStateAction<string | null>>;
  selectedQueueRow: QueueRow | null;
  failedModalRow: Delivery | null;
  setFailedModalRow: React.Dispatch<React.SetStateAction<Delivery | null>>;
  
  // Action State
  runningAction: string | null;
  setRunningAction: React.Dispatch<React.SetStateAction<string | null>>;
  pendingAction: PendingAction | null;
  setPendingAction: React.Dispatch<React.SetStateAction<PendingAction | null>>;
  actionNote: string;
  setActionNote: React.Dispatch<React.SetStateAction<string>>;
  replanScheduledAt: string;
  setReplanScheduledAt: React.Dispatch<React.SetStateAction<string>>;
  
  // Overlays State
  drawerTargets: ReassignTarget[];
  setDrawerTargets: React.Dispatch<React.SetStateAction<ReassignTarget[]>>;
  cancelTarget: OpsException | null;
  setCancelTarget: React.Dispatch<React.SetStateAction<OpsException | null>>;
  cancelReason: string;
  setCancelReason: React.Dispatch<React.SetStateAction<string>>;
  cancelling: boolean;
  returnTarget: OpsException | null;
  setReturnTarget: React.Dispatch<React.SetStateAction<OpsException | null>>;
  returnNote: string;
  setReturnNote: React.Dispatch<React.SetStateAction<string>>;
  confirmingReturn: boolean;
  
  // Selection / Batch State
  selectedIds: Set<string>;
  setSelectedIds: React.Dispatch<React.SetStateAction<Set<string>>>;
  toggleRow: (id: string) => void;
  toggleAll: () => void;
  allSelected: boolean;
  someSelected: boolean;
  selectedTargets: ReassignTarget[];
  batchType: 'assign' | 'reassign' | 'mixed' | 'none';
  
  // Computed views
  alertMap: Map<string, OpsException>;
  deliveryMap: Map<string, Delivery>;
  actionRows: OpsException[];
  deliveryRows: Delivery[];
  queueRows: QueueRow[];
  routeOptions: { value: string; label: string }[];
  tabCounts: { queue: number; assign: number; action: number; failed: number; gps: number };

  // Action triggers
  doRefresh: () => void;
  fetchExceptions: (silent?: boolean) => Promise<void>;
  fetchAllDeliveries: () => Promise<void>;
  resetActionState: () => void;
  openActionModal: (kind: ActionKind, row: OpsException) => void;
  confirmAction: () => Promise<void>;
  runCancel: () => Promise<void>;
  runConfirmReturn: () => Promise<void>;
}

const DispatchDeskContext = createContext<DispatchDeskContextProps | undefined>(undefined);

export function useDispatchDeskContext() {
  const context = useContext(DispatchDeskContext);
  if (!context) {
    throw new Error('useDispatchDeskContext must be used within a DispatchDeskProvider');
  }
  return context;
}

export function DispatchDeskProvider({ children }: { children: React.ReactNode }) {
  const t = useT();
  usePageBreadcrumb([{ label: t.pages.dispatch?.title || 'Dispatch' }]);
  const isReadOnly = isReadOnlyRole(getCurrentRole());
  const { filters: globalFilters, applyFilters, clearFilters: clearGlobalFilters, globalContext } = useGlobalFilters();

  // ── Data ──────────────────────────────────────────────────────────────────
  const [rows, setRows]                   = useState<OpsException[]>([]);
  const [drivers, setDrivers]             = useState<Driver[]>([]);
  const [zones, setZones]                 = useState<Zone[]>([]);
  const [allDeliveries, setAllDeliveries] = useState<Delivery[]>([]);

  // ── Filters ───────────────────────────────────────────────────────────────
  const [period, setPeriod]           = useState<Period>('all');
  const [customFrom, setCustomFrom]   = useState('');
  const [customTo, setCustomTo]       = useState('');
  const [search, setSearch]           = useState(globalFilters.search || '');
  const [driverId, setDriverId]       = useState(globalFilters.driver || '');
  const [zoneFilter, setZoneFilter]   = useState(globalFilters.zone || '');
  const [statusFilter, setStatusFilter] = useState('');
  const [routeFilter, setRouteFilter] = useState('');
  const [queueSort, setQueueSort] = useState<QueueSortMode>('route');

  // ── UI state ──────────────────────────────────────────────────────────────
  const [dispatchTab, setDispatchTab]   = useState<DispatchTab>('queue');
  const [mobileTab, setMobileTab]       = useState<'filters' | 'list'>('list');
  const [filtersOpen, setFiltersOpen]   = useState(true);
  const [expandedActionId, setExpandedActionId] = useState<string | null>(null);
  const [selectedQueueId, setSelectedQueueId] = useState<string | null>(null);
  const [failedModalRow, setFailedModalRow]     = useState<Delivery | null>(null);

  // ── Loading ───────────────────────────────────────────────────────────────
  const [loading, setLoading]       = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [allLoading, setAllLoading] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [newSinceLoad, setNewSinceLoad] = useState(0);
  const prevCountRef = useRef<number | null>(null);

  // ── Actions ───────────────────────────────────────────────────────────────
  const [runningAction, setRunningAction] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const [actionNote, setActionNote]       = useState('');
  // New scheduled date for replan (datetime-local string). Overrides the stale ERP date for SLA.
  const [replanScheduledAt, setReplanScheduledAt] = useState('');

  // ── Overlays ──────────────────────────────────────────────────────────────
  const [drawerTargets, setDrawerTargets]     = useState<ReassignTarget[]>([]);
  const [cancelTarget, setCancelTarget]       = useState<OpsException | null>(null);
  const [cancelReason, setCancelReason]       = useState('');
  const [cancelling, setCancelling]           = useState(false);
  const [returnTarget, setReturnTarget]       = useState<OpsException | null>(null);
  const [returnNote, setReturnNote]           = useState('');
  const [confirmingReturn, setConfirmingReturn] = useState(false);

  // ── Batch ─────────────────────────────────────────────────────────────────
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  // ── Misc ──────────────────────────────────────────────────────────────────
  const [currentUser, setCurrentUser] = useState<any>(null);
  const router = useRouter();
  const [searchParams] = useSearchParams();
  const initialSyncRef = useRef(false);

  // ── Fetchers ──────────────────────────────────────────────────────────────

  const fetchDrivers = useCallback(async () => {
    try { const r = await api.get('/api/admin/fleet/drivers'); setDrivers(Array.isArray(r.data) ? r.data : []); }
    catch { setDrivers([]); }
  }, []);

  const fetchZones = useCallback(async () => {
    try { const r = await api.get('/api/v1/zones/active'); setZones(Array.isArray(r.data) ? r.data : []); }
    catch { setZones([]); }
  }, []);

  const fetchExceptions = useCallback(async (silent = false) => {
    if (silent) setRefreshing(true); else setLoading(true);
    try {
      const params: Record<string, string | number> = { period, limit: 200 };
      if (period === 'custom') {
        if (!customFrom || !customTo) { setRows([]); return; }
        params.from = customFrom; params.to = customTo;
      }
      const res = await api.get<OpsExceptionResponse>('/api/admin/ops/exceptions', { params });
      const items = Array.isArray(res.data?.items) ? res.data.items : [];
      setRows(items);
      setLastUpdated(new Date());
      if (silent && prevCountRef.current !== null && items.length > prevCountRef.current)
        setNewSinceLoad(n => n + (items.length - prevCountRef.current!));
      prevCountRef.current = items.length;
    } catch { if (!silent) showErrorToast(t.apiMessages.errorDataLoadFailed, t.dispatchDeskPage.errorLoadingAlerts); }
    finally { setLoading(false); setRefreshing(false); }
  }, [customFrom, customTo, period, t]);

  const fetchAllDeliveries = useCallback(async () => {
    setAllLoading(true);
    try {
      const params: Record<string, string | number> = { size: 500 };
      if (statusFilter) params.status = statusFilter;
      if (period === 'day')    params.date = new Date().toISOString().slice(0, 10);
      else if (period === 'week')  { params.dateFrom = getWeekStart();  params.dateTo = new Date().toISOString().slice(0, 10); }
      else if (period === 'month') { params.dateFrom = getMonthStart(); params.dateTo = new Date().toISOString().slice(0, 10); }
      else if (period === 'custom' && customFrom && customTo) { params.dateFrom = customFrom; params.dateTo = customTo; }
      const res = await api.get('/api/admin/deliveries', { params });
      setAllDeliveries(Array.isArray(res.data?.content) ? res.data.content : []);
    } catch { /* silent */ }
    finally { setAllLoading(false); }
  }, [statusFilter, period, customFrom, customTo]);

  // ── Effects ───────────────────────────────────────────────────────────────

  useEffect(() => { setCurrentUser(getCurrentUser()); fetchDrivers(); fetchZones(); }, [fetchDrivers, fetchZones]);
  useEffect(() => { void fetchExceptions(); }, [fetchExceptions]);
  useEffect(() => { void fetchAllDeliveries(); }, [fetchAllDeliveries]);

  // Realtime: push beats the old 45s poll. Any delivery/assignment/departure lifecycle event
  // triggers a debounced refetch (a burst of events → one refetch), so the queue reflects the
  // field within ~1s. A slow 120s interval stays as a safety net if the socket drops.
  const rtTimer = useRef<number | null>(null);
  const refetchBoth = useCallback(() => { void fetchExceptions(true); void fetchAllDeliveries(); }, [fetchExceptions, fetchAllDeliveries]);
  useRealtimeEvent(
    ['delivery.created', 'delivery.scheduled', 'delivery.reassigned', 'delivery.reassigned_away',
     'delivery.replanned', 'delivery.picked_up', 'delivery.in_transit', 'delivery.completed',
     'delivery.failed', 'delivery.cancelled', 'delivery.awaiting', 'delivery.redelivery_scheduled',
     'assignment.awaiting', 'departure.awaiting', 'departure.loading'],
    () => {
      if (rtTimer.current != null) return;
      rtTimer.current = window.setTimeout(() => { rtTimer.current = null; refetchBoth(); }, 1500);
    },
  );
  useEffect(() => {
    const id = setInterval(refetchBoth, 120_000); // safety fallback only
    return () => { clearInterval(id); if (rtTimer.current != null) window.clearTimeout(rtTimer.current); };
  }, [refetchBoth]);

  // Reset on leave: dispatch filters are per-visit. Clearing the shared operational-filter store on
  // unmount means returning to the desk — or arriving via a notification deep-link (?search=…) —
  // always starts from a clean state, and a notification's search never lingers as a stale filter.
  useEffect(() => () => { clearGlobalFilters(); }, [clearGlobalFilters]);

  useEffect(() => {
    if (!globalContext || initialSyncRef.current) return;
    initialSyncRef.current = true;
    const tabParam = searchParams?.get('tab');
    if (tabParam === 'queue' || tabParam === 'gps' || tabParam === 'handoff') {
      setDispatchTab(tabParam);
    } else if (tabParam === 'action' || tabParam === 'assign' || tabParam === 'failed') {
      // Legacy deep links — both tabs were merged into the unified Queue.
      setDispatchTab('queue');
    }
    const q = searchParams?.get('search') || searchParams?.get('deliveryId');
    if (q) { setSearch(q); applyFilters({ search: q }); }
    else { setSearch(globalFilters.search); setZoneFilter(globalFilters.zone); setDriverId(globalFilters.driver); }
  }, [globalContext, searchParams, applyFilters, globalFilters.search, globalFilters.zone, globalFilters.driver]);

  // ── Computed ──────────────────────────────────────────────────────────────

  // The backend emits one exception per delivery, but dedup defensively by
  // deliveryId and keep the most severe so a WARNING can never mask a CRITICAL
  // (the residual half of the SLA_WAITING / SLA_UNSCHEDULED_LATE overlap).
  const alertMap = useMemo(() => {
    const sev = (s?: string) => (s === 'CRITICAL' ? 3 : s === 'WARNING' ? 2 : 1);
    const m = new Map<string, OpsException>();
    for (const r of rows) {
      const existing = m.get(r.deliveryId);
      if (!existing || sev(r.severity) > sev(existing.severity)) m.set(r.deliveryId, r);
    }
    return m;
  }, [rows]);
  const deliveryMap = useMemo(() => new Map(allDeliveries.map(d => [rowId(d), d])), [allDeliveries]);

  const matchSearch = useCallback((clientName?: string, ref?: string, erpId?: string, id?: string) => {
    const q = search.toLowerCase();
    if (!q) return true;
    return `${clientName ?? ''} ${ref ?? ''} ${erpId ?? ''} ${id ?? ''}`.toLowerCase().includes(q);
  }, [search]);

  const actionRows = useMemo(() => {
    const filtered = rows.filter(r => {
      if (!matchSearch(r.clientName, r.orderRef, undefined, r.deliveryId)) return false;
      if (driverId && r.driverId !== driverId) return false;
      if (zoneFilter && r.zoneName !== zoneFilter && r.city !== zoneFilter) return false;
      if (routeFilter && r.routeId !== routeFilter) return false;
      return true;
    });
    return sortByRoute(filtered, r => r.severity === 'CRITICAL' ? 0 : r.severity === 'WARNING' ? 1 : 2);
  }, [rows, matchSearch, driverId, zoneFilter, routeFilter]);

  const routeOptions = useMemo(() => {
    const seen = new Map<string, string>();
    rows.forEach(r => { if (r.routeId && r.routeName) seen.set(r.routeId, r.routeName); });
    return Array.from(seen.entries()).map(([value, label]) => ({ value, label }));
  }, [rows]);

  const deliveryRows = useMemo(() => {
    const result = allDeliveries.filter(d => {
      const id = rowId(d);
      if (!matchSearch(d.clientName, d.orderRef, d.erpOrderId, id)) return false;
      if (driverId && d.driverId !== driverId) return false;
      if (zoneFilter && d.zoneName !== zoneFilter && d.dropoffCity !== zoneFilter) return false;
      if (d.status === 'CANCELLED') return false; // annulées exclues du dispatch desk
      if (dispatchTab === 'assign') return (ASSIGNABLE_STATUSES as string[]).includes(d.status);
      if (dispatchTab === 'gps')    return !d.dropoffLat || !d.dropoffLng;
      return true;
    });
    return sortByRoute(result, d => {
      if (dispatchTab === 'assign' && d.scheduledAt) {
        const dateStr = d.scheduledAt.split('T')[0];
        const now = new Date();
        const todayStr = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().split('T')[0];
        if (dateStr < todayStr) return 0;
        if (dateStr === todayStr) return 1;
        return 2;
      }
      const s = alertMap.get(rowId(d))?.severity;
      return s === 'CRITICAL' ? 0 : s === 'WARNING' ? 1 : s ? 2 : 3;
    });
  }, [allDeliveries, matchSearch, driverId, zoneFilter, dispatchTab, alertMap]);

  // Unified Queue: every delivery that needs attention — either it's awaiting
  // assignment (ASSIGNABLE_STATUSES) or it carries an active ops alert — merged
  // and deduped so the same order never renders twice (the root cause of the
  // Assign/Action tab overlap).
  const queueRows = useMemo((): QueueRow[] => {
    const byId = new Map<string, QueueRow>();

    allDeliveries.forEach(d => {
      const id = rowId(d);
      if (!matchSearch(d.clientName, d.orderRef, d.erpOrderId, id)) return;
      if (driverId && d.driverId !== driverId) return;
      if (zoneFilter && d.zoneName !== zoneFilter && d.dropoffCity !== zoneFilter) return;
      if (!(ASSIGNABLE_STATUSES as string[]).includes(d.status)) return;
      byId.set(id, { id, delivery: d, alert: alertMap.get(id), routeId: d.routeId, routeName: d.routeName });
    });

    rows.forEach(r => {
      if (byId.has(r.deliveryId)) return;
      const d = deliveryMap.get(r.deliveryId);
      if (!d) return;
      if (d.status === 'CANCELLED') return; // annulées exclues du dispatch desk
      if (!matchSearch(r.clientName, r.orderRef, undefined, r.deliveryId)) return;
      if (driverId && r.driverId !== driverId) return;
      if (zoneFilter && r.zoneName !== zoneFilter && r.city !== zoneFilter) return;
      byId.set(r.deliveryId, { id: r.deliveryId, delivery: d, alert: r, routeId: r.routeId ?? d.routeId, routeName: r.routeName ?? d.routeName });
    });

    return sortQueue(Array.from(byId.values()), queueSort);
  }, [allDeliveries, rows, alertMap, deliveryMap, matchSearch, driverId, zoneFilter, queueSort]);

  const selectedQueueRow = useMemo(
    () => queueRows.find(q => q.id === selectedQueueId) ?? null,
    [queueRows, selectedQueueId]
  );

  const tabCounts = useMemo(() => ({
    queue:  queueRows.length,
    assign: allDeliveries.filter(d => (ASSIGNABLE_STATUSES as string[]).includes(d.status)).length,
    action: rows.length,
    failed: allDeliveries.filter(d => d.status === 'FAILED').length,
    gps:    allDeliveries.filter(d => (!d.dropoffLat || !d.dropoffLng) && d.status !== 'CANCELLED').length,
  }), [allDeliveries, rows, queueRows]);

  const allFilteredIds = useMemo(() => {
    if (dispatchTab === 'queue')  return queueRows.map(q => q.id);
    if (dispatchTab === 'action') return actionRows.map(r => r.deliveryId);
    return deliveryRows.map(d => rowId(d));
  }, [dispatchTab, queueRows, actionRows, deliveryRows]);

  const allSelected  = allFilteredIds.length > 0 && allFilteredIds.every(id => selectedIds.has(id));
  const someSelected = allFilteredIds.some(id => selectedIds.has(id));

  const selectedTargets = useMemo((): ReassignTarget[] => {
    if (dispatchTab === 'queue')
      return queueRows.filter(q => selectedIds.has(q.id))
        .map(q => ({ deliveryId: q.id, orderRef: q.delivery.orderRef, erpOrderId: q.delivery.erpOrderId, clientName: q.delivery.clientName, city: q.delivery.dropoffCity, status: q.delivery.status, driverName: q.delivery.driverName, routeId: q.routeId, routeName: q.routeName, timeSlotStartTime: q.delivery.timeSlotStartTime, timeSlotEndTime: q.delivery.timeSlotEndTime, totalWeightKg: q.delivery.totalWeightKg, totalAmount: q.delivery.totalAmount, currency: q.delivery.currency, itemsCount: q.delivery.items?.length, priority: q.delivery.priority, scheduledAt: q.delivery.scheduledAt, dropoffAddress: q.delivery.dropoffAddress }));
    if (dispatchTab === 'action')
      return actionRows.filter(r => selectedIds.has(r.deliveryId))
        .map(r => ({ deliveryId: r.deliveryId, orderRef: r.orderRef, clientName: r.clientName, city: r.city, status: r.status, driverName: r.driverName, routeId: r.routeId, routeName: r.routeName }));
    return deliveryRows.filter(d => selectedIds.has(rowId(d)))
      .map(d => ({ deliveryId: rowId(d), orderRef: d.orderRef, erpOrderId: d.erpOrderId, clientName: d.clientName, city: d.dropoffCity, status: d.status, driverName: d.driverName, routeId: d.routeId, routeName: d.routeName, timeSlotStartTime: d.timeSlotStartTime, timeSlotEndTime: d.timeSlotEndTime, totalWeightKg: d.totalWeightKg, totalAmount: d.totalAmount, currency: d.currency, itemsCount: d.items?.length, priority: d.priority, scheduledAt: d.scheduledAt, dropoffAddress: d.dropoffAddress }));
  }, [dispatchTab, queueRows, actionRows, deliveryRows, selectedIds]);

  const batchType: 'assign' | 'reassign' | 'mixed' | 'none' = useMemo(() => {
    if (selectedTargets.length === 0) return 'none';
    if (selectedTargets.every(d => d.status === 'UNSCHEDULED')) return 'assign';
    if (selectedTargets.every(d => (REASSIGNABLE_STATUSES as string[]).includes(d.status ?? ''))) return 'reassign';
    return 'mixed';
  }, [selectedTargets]);

  const toggleRow = useCallback((id: string) => setSelectedIds(prev => {
    const n = new Set(prev);
    n.has(id) ? n.delete(id) : n.add(id);
    return n;
  }), []);

  const toggleAll = useCallback(() => {
    if (allSelected) {
      setSelectedIds(prev => {
        const n = new Set(prev);
        allFilteredIds.forEach(id => n.delete(id));
        return n;
      });
    } else {
      setSelectedIds(prev => {
        const n = new Set(prev);
        allFilteredIds.forEach(id => n.add(id));
        return n;
      });
    }
  }, [allSelected, allFilteredIds]);

  const clearFilters = useCallback(() => {
    setSearch('');
    setDriverId('');
    setZoneFilter('');
    setStatusFilter('');
    setRouteFilter('');
  }, []);

  // ── Action handlers ───────────────────────────────────────────────────────

  const resetActionState = useCallback(() => {
    setPendingAction(null);
    setActionNote('');
    setReplanScheduledAt('');
  }, []);

  const openActionModal = useCallback((kind: ActionKind, row: OpsException) => {
    if (kind === 'reassign' && !REASSIGNABLE_STATUSES.includes(row.status)) {
      showErrorToast(t.apiMessages.errorDeliveryInvalidStatus, t.dispatchDeskPage.errorCannotReassign);
      return;
    }
    if (kind === 'replan' && !REPLANNABLE_STATUSES.includes(row.status)) {
      showErrorToast(t.apiMessages.errorDeliveryInvalidStatus, t.dispatchDeskPage.errorCannotReplan);
      return;
    }
    if (kind === 'reassign') {
      setDrawerTargets([{
        deliveryId: row.deliveryId,
        orderRef: row.orderRef,
        clientName: row.clientName,
        city: row.city,
        status: row.status,
        driverName: row.driverName,
        routeId: row.routeId,
        routeName: row.routeName
      }]);
      return;
    }
    setPendingAction({ kind, row });
    const actorPart = currentUser?.name ? ` ${t.dispatchDeskPage.byLabel} ${currentUser.name}` : '';
    if (kind === 'replan') {
      setActionNote(`${t.dispatchDeskPage.reprogrammationLabel}${actorPart} — ${formatMotif(row.motif, t)}`);
    }
  }, [currentUser, t]);

  const runReplan = useCallback(async (deliveryId: string, note: string, scheduledAt?: string) => {
    if (runningAction) return;
    setRunningAction(`replan:${deliveryId}`);
    try {
      const payload: { note: string; scheduledAt?: string } = { note };
      // datetime-local has no seconds — backend LocalDateTime parses 'yyyy-MM-ddTHH:mm'.
      if (scheduledAt && scheduledAt.trim()) payload.scheduledAt = scheduledAt.trim();
      await api.post(`/api/admin/ops/exceptions/${deliveryId}/replan`, payload);
      showSuccessToast(t.apiMessages.successDeliveryRescheduled);
      await fetchExceptions(true);
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.dispatchDeskPage.errorReplan);
    } finally {
      setRunningAction(null);
    }
  }, [runningAction, fetchExceptions, t]);

  const confirmAction = useCallback(async () => {
    if (!pendingAction || runningAction) return;
    const note = actionNote.trim();
    if (!note) {
      showErrorToast(null, t.dispatchDeskPage.errorNoteRequired);
      return;
    }
    if (pendingAction.kind === 'replan') {
      await runReplan(pendingAction.row.deliveryId, note, replanScheduledAt);
      resetActionState();
    }
  }, [pendingAction, runningAction, actionNote, replanScheduledAt, runReplan, resetActionState, t]);

  const runCancel = useCallback(async () => {
    if (!cancelTarget || !cancelReason.trim()) return;
    setCancelling(true);
    try {
      await api.post(`/api/admin/deliveries/${cancelTarget.deliveryId}/cancel`, null, { params: { reason: cancelReason.trim() } });
      showSuccessToast(t.apiMessages.successDeliveryRescheduled, { clientName: cancelTarget?.clientName });
      setCancelTarget(null);
      setCancelReason('');
      await fetchExceptions(true);
      await fetchAllDeliveries();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.dispatchDeskPage.errorCancel, { clientName: cancelTarget?.clientName });
    } finally {
      setCancelling(false);
    }
  }, [cancelTarget, cancelReason, fetchExceptions, fetchAllDeliveries, t]);

  const runConfirmReturn = useCallback(async () => {
    if (!returnTarget) return;
    setConfirmingReturn(true);
    try {
      await api.post(`/api/admin/deliveries/${returnTarget.deliveryId}/confirm-return`, null,
        { params: returnNote.trim() ? { note: returnNote.trim() } : {} });
      showSuccessToast(t.apiMessages.successDeliveryReassigned, { clientName: returnTarget?.clientName });
      setReturnTarget(null);
      setReturnNote('');
      await fetchExceptions(true);
      await fetchAllDeliveries();
    } catch (err: any) {
      showErrorToast(err?.response?.data?.message, t.dispatchDeskPage.errorReturnConfirm, { clientName: returnTarget?.clientName });
    } finally {
      setConfirmingReturn(false);
    }
  }, [returnTarget, returnNote, fetchExceptions, fetchAllDeliveries, t]);

  const doRefresh = useCallback(() => {
    void fetchExceptions(true);
    void fetchAllDeliveries();
  }, [fetchExceptions, fetchAllDeliveries]);

  const value = useMemo((): DispatchDeskContextProps => ({
    t,
    isReadOnly,
    rows,
    drivers,
    zones,
    allDeliveries,
    loading,
    refreshing,
    allLoading,
    lastUpdated,
    newSinceLoad,
    setNewSinceLoad,
    period,
    setPeriod,
    customFrom,
    setCustomFrom,
    customTo,
    setCustomTo,
    search,
    setSearch,
    driverId,
    setDriverId,
    zoneFilter,
    setZoneFilter,
    statusFilter,
    setStatusFilter,
    routeFilter,
    setRouteFilter,
    queueSort,
    setQueueSort,
    clearFilters,
    dispatchTab,
    setDispatchTab,
    mobileTab,
    setMobileTab,
    filtersOpen,
    setFiltersOpen,
    expandedActionId,
    setExpandedActionId,
    selectedQueueId,
    setSelectedQueueId,
    selectedQueueRow,
    failedModalRow,
    setFailedModalRow,
    runningAction,
    setRunningAction,
    pendingAction,
    setPendingAction,
    actionNote,
    setActionNote,
    replanScheduledAt,
    setReplanScheduledAt,
    drawerTargets,
    setDrawerTargets,
    cancelTarget,
    setCancelTarget,
    cancelReason,
    setCancelReason,
    cancelling,
    returnTarget,
    setReturnTarget,
    returnNote,
    setReturnNote,
    confirmingReturn,
    selectedIds,
    setSelectedIds,
    toggleRow,
    toggleAll,
    allSelected,
    someSelected,
    selectedTargets,
    batchType,
    alertMap,
    deliveryMap,
    actionRows,
    deliveryRows,
    queueRows,
    routeOptions,
    tabCounts,
    doRefresh,
    fetchExceptions,
    fetchAllDeliveries,
    resetActionState,
    openActionModal,
    confirmAction,
    runCancel,
    runConfirmReturn,
  }), [
    t,
    isReadOnly,
    rows,
    drivers,
    zones,
    allDeliveries,
    loading,
    refreshing,
    allLoading,
    lastUpdated,
    newSinceLoad,
    period,
    customFrom,
    customTo,
    search,
    driverId,
    zoneFilter,
    statusFilter,
    routeFilter,
    clearFilters,
    dispatchTab,
    mobileTab,
    filtersOpen,
    expandedActionId,
    selectedQueueId,
    selectedQueueRow,
    failedModalRow,
    runningAction,
    pendingAction,
    actionNote,
    replanScheduledAt,
    drawerTargets,
    cancelTarget,
    cancelReason,
    cancelling,
    returnTarget,
    returnNote,
    confirmingReturn,
    selectedIds,
    toggleRow,
    toggleAll,
    allSelected,
    someSelected,
    selectedTargets,
    batchType,
    alertMap,
    deliveryMap,
    actionRows,
    deliveryRows,
    queueRows,
    routeOptions,
    tabCounts,
    doRefresh,
    fetchExceptions,
    fetchAllDeliveries,
    resetActionState,
    openActionModal,
    confirmAction,
    runCancel,
    runConfirmReturn,
  ]);

  return React.createElement(DispatchDeskContext.Provider, { value }, children);
}

