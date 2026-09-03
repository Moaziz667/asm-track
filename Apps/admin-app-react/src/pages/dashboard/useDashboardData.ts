import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { AdminOpsOverview, AnalyticsScope, DashboardStats, DeliveryStatus, Driver } from '@/types';
import { useRealtimeEvent, useRealtimeStatus } from '@/components/RealtimeProvider';
import { useT } from '@/lib/i18n/LocaleContext';
import { useRoutes } from '@/hooks/useRoutes';
import { getBusinessDayKey } from '@/lib/sla';
import { deriveHealthSummary } from '@/lib/health/system-health';
import { useGlobalMapStore } from '@/lib/state/global-map-store';
import { DISPATCH_STATUSES, DASHBOARD_EVENTS, STATUS_TONE_MAP } from './constants';
import { analyticsDateParams } from '@/lib/analytics/date-params';

export type Range = 'today' | 'yesterday' | 'last7d' | 'last30d' | 'custom';

export type ReturnsKpi = { total?: number; open?: number; restocked?: number; totalValue?: number };

/** A KPI delta that is either a percentage change (previous>0) or an absolute count delta
 *  (previous=0). `value` is null when there is nothing to compare. */
export type TrendDelta = { value: number | null; isPct: boolean };
const EMPTY_DELTA: TrendDelta = { value: null, isPct: true };

/** All data + derived metrics for the dashboard. Extracted from DashboardPage so the page is a
 *  pure layout/orchestrator. Source of truth = REST via React Query; realtime events only
 *  invalidate (debounced), so KPIs self-heal from the server.
 *  Only the stats/analytics half honors the date range + scope; the live half is "now". */
export function useDashboardData(range: Range, from?: string, to?: string, scope: AnalyticsScope = {}) {
  const t = useT();
  const queryClient = useQueryClient();
  const connected = useRealtimeStatus();

  // Granular date params: custom sends explicit from/to, presets send the range key. A bare custom
  // (dates not yet entered) falls back to a valid preset so the request never 400s — see analyticsDateParams.
  const dateParams = analyticsDateParams(range, from, to);

  // Merge scope filters into API params (only non-empty values).
  const scopeParams = useMemo(() => {
    const p: Record<string, string[]> = {};
    if (scope.zone?.length) p.zone = scope.zone;
    if (scope.driverId?.length) p.driverId = scope.driverId;
    if (scope.status?.length) p.status = scope.status;
    if (scope.motif?.length) p.motif = scope.motif;
    if (scope.city?.length) p.city = scope.city;
    if (scope.depot?.length) p.depot = scope.depot;
    return p;
  }, [scope]);

  const { data: dash, isFetching: refreshing, isLoading, refetch } = useQuery({
    queryKey: ['dashboard-overview', range, from ?? '', to ?? '', scopeParams],
    queryFn: async () => {
      const [sR, oR, driversRes, routesRes, kR, healthRes, returnsRes, countsRes] = await Promise.all([
        api.get('/admin/deliveries/stats', { params: { ...dateParams, ...scopeParams } }),
        // LIVE plane — always "now"; only spatial pivots scope it, never the date range.
        api.get('/admin/ops/overview', { params: { ...scopeParams } }),
        api.get('/admin/fleet/drivers').catch(() => ({ data: [] })),
        api.get('/admin/routes', { params: { status: 'IN_PROGRESS' } }).catch(() => ({ data: [] })),
        api.get('/admin/reports/dashboard', { params: { ...dateParams, ...scopeParams, compare: true } }).catch(() => ({ data: null })),
        api.get('/admin/system/health').catch(() => ({ data: null })),
        // Returns KPI + delivery backlog tallies — live "now" (no date range).
        api.get('/admin/returns/kpi').catch(() => ({ data: null })),
        api.get('/admin/deliveries/counts').catch(() => ({ data: null })),
      ]);
      const driversData = driversRes.data;
      return {
        stats: (sR.data ?? null) as DashboardStats | null,
        ops: (oR.data ?? null) as AdminOpsOverview | null,
        kpi: kR.data ?? null,
        drivers: (Array.isArray(driversData) ? driversData : (driversData?.content ?? driversData?.drivers ?? [])) as Driver[],
        activeRoutesCount: Array.isArray(routesRes.data) ? routesRes.data.length : 0,
        health: healthRes.data,
        returns: (returnsRes.data ?? null) as ReturnsKpi | null,
        counts: (countsRes.data ?? null) as Record<string, number> | null,
      };
    },
    staleTime: 30_000,
  });

  const stats = dash?.stats ?? null;
  const ops = dash?.ops ?? null;
  const kpi = dash?.kpi ?? null;
  const drivers = dash?.drivers ?? [];
  const activeRoutesCount = dash?.activeRoutesCount ?? 0;
  const healthData = dash?.health ?? null;
  const returns = dash?.returns ?? null;
  const counts = dash?.counts ?? null;

  const healthSummary = useMemo(() => deriveHealthSummary(healthData), [healthData]);

  const healthProblemsSummary = useMemo(() => {
    if (!healthData) return '';
    const downCbs = (healthData.circuitBreakers ?? []).filter(
      (cb: { state?: string; reachable?: boolean; name: string }) => cb.state === 'OPEN' || cb.state === 'FORCED_OPEN' || cb.reachable === false
    );
    const parts: string[] = [];
    if (downCbs.length > 0) {
      const names = Array.from(new Set(downCbs.map((cb: { state?: string; reachable?: boolean; name: string }) => {
        const n = cb.name.toLowerCase();
        if (n.includes('erp') || n.includes('odoo')) return t.dashboardPage.systemHealthCategoryErp || 'ERP';
        if (n.includes('keycloak') || n.includes('auth')) return t.dashboardPage.systemHealthCategoryAuth || 'Auth';
        if (n.includes('route') || n.includes('osrm') || n.includes('geocode')) return t.dashboardPage.systemHealthCategoryRoutes || 'Carto';
        if (n.includes('driver')) return t.dashboardPage.systemHealthCategoryDrivers || 'Chauffeurs';
        return cb.name;
      })));
      parts.push(`${names.join(', ')}`);
    }
    if (healthData.db?.reachable === false) parts.push(t.dashboardPage.systemHealthDb || 'Base de données');
    const stuckQueuesCount = Object.entries(healthData.dlq ?? {}).filter(([, v]) => Number(v) > 0).length;
    if (stuckQueuesCount > 0) parts.push(t.dashboardPage.systemHealthDlq?.replace('{count}', String(stuckQueuesCount)) || `${stuckQueuesCount} file(s) DLQ`);
    if ((healthData.erpSync?.failed ?? 0) > 0) parts.push(t.dashboardPage.systemHealthErpSync || 'Synchro ERP');
    return parts.join(', ');
  }, [healthData, t]);

  // Coalesce a burst of events into a single background refetch (leading timer).
  const invalidateTimer = useRef<number | null>(null);
  useRealtimeEvent(DASHBOARD_EVENTS, () => {
    if (invalidateTimer.current != null) return;
    invalidateTimer.current = window.setTimeout(() => {
      invalidateTimer.current = null;
      queryClient.invalidateQueries({ queryKey: ['dashboard-overview'] });
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    }, 1500);
  });

  // Cleanup timer on unmount to prevent memory leak.
  useEffect(() => {
    return () => {
      if (invalidateTimer.current != null) {
        clearTimeout(invalidateTimer.current);
        invalidateTimer.current = null;
      }
    };
  }, []);

  // Reconnect catch-up: refetch once when the socket re-establishes.
  const prevConnected = useRef(connected);
  useEffect(() => {
    if (connected && !prevConnected.current) {
      queryClient.invalidateQueries({ queryKey: ['dashboard-overview'] });
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    }
    prevConnected.current = connected;
  }, [connected, queryClient]);

  const [todayIso, setTodayIso] = useState(() => getBusinessDayKey());
  // Recalculate at midnight so the business day stays correct across day boundaries.
  useEffect(() => {
    const now = new Date();
    const msUntilMidnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1).getTime() - now.getTime();
    const timer = setTimeout(() => setTodayIso(getBusinessDayKey()), msUntilMidnight + 1000);
    return () => clearTimeout(timer);
  }, [todayIso]);
  const { data: todayRoutes = [] } = useRoutes({ from: todayIso, to: todayIso });
  const activeRoutes = useMemo(
    () => todayRoutes.filter(r => r.status === 'VALIDATED' || r.status === 'IN_PROGRESS'),
    [todayRoutes],
  );
  const { focusedRouteId, setFocusedRouteId } = useGlobalMapStore();

  const driverName = useCallback((id: string | undefined) => {
    if (!id) return 'Non assigné';
    const d = drivers.find(d => d.id === id);
    return d ? d.name : id;
  }, [drivers]);

  const getStatusConfig = useCallback((status: DeliveryStatus): { label: string; tone: string } => ({
    label: t.statusLabels[status] || status,
    tone: STATUS_TONE_MAP[status],
  }), [t]);

  const today = stats?.today;
  // slaBreached, pas totalBreaches : le second est la somme des trois anciens compteurs par phase,
  // et il donnait un nombre different de celui du widget « En depassement » sur la meme page. Le
  // backend designe slaBreached comme la source de verite — c'est le decompte du moteur SLA unifie.
  const overdueCount = ops?.sla?.slaBreached ?? 0;
  // SLA = on-time compliance from the backend (period, event-based), NOT delivered/total (completion).
  const slaPercent = kpi?.slaRate != null
    ? Math.round(kpi.slaRate)
    : (today?.total ? Math.round((today.delivered / today.total) * 100) : 100);

  const trend = useMemo(
    () => (Array.isArray(kpi?.weeklyTrend) ? kpi.weeklyTrend : []) as Array<{ count: number; delivered: number; failed: number; late?: number; slaRate?: number }>,
    [kpi],
  );
  // Top-KPI deltas — all period-over-period vs the preceding window of equal length (backend compare),
  // so they track the selected range instead of a fixed last-7-vs-prior-7 slice of the 30-day trend.
  // Robust rendering: previous>0 → % change; previous=0 but current>0 → absolute delta (a % change
  // vs zero is undefined); both 0 → nothing. So every count KPI shows an honest comparison.
  const trendDelta = (cur: number, prev: number): TrendDelta => {
    if (prev > 0) return { value: ((cur - prev) / prev) * 100, isPct: true };
    if (cur > 0) return { value: cur - prev, isPct: false };
    return { value: null, isPct: true };
  };
  const deliveredDelta = kpi ? trendDelta(Number(kpi.deliveredOrders) || 0, Number(kpi.previousDelivered) || 0) : EMPTY_DELTA;
  const failedDelta = kpi ? trendDelta(Number(kpi.failedOrders) || 0, Number(kpi.previousFailed) || 0) : EMPTY_DELTA;
  const lateDelta = kpi ? trendDelta(Number(kpi.lateOrders) || 0, Number(kpi.previousLate) || 0) : EMPTY_DELTA;
  // SLA delta (points) only when BOTH windows have measurable deliveries. Otherwise the previous SLA is
  // a 100% default (empty reference window) and "−50 pts" would be a misleading artifact → show nothing.
  const slaDelta = (kpi?.slaRate != null && kpi?.previousSlaRate != null
    && (Number(kpi.measurableOrders) || 0) > 0 && (Number(kpi.previousMeasurable) || 0) > 0)
    ? kpi.slaRate - kpi.previousSlaRate : null;

  const vsPrev = t.dashboardPage.kpiVsPrevPeriod || 'vs prev. period';
  const deliveredSub = deliveredDelta == null ? `/ ${today?.total ?? 0}` : undefined;

  const safeDrivers = useMemo(() => drivers.map(d => ({
    ...d, currentLat: Number(d.currentLat) || null, currentLng: Number(d.currentLng) || null,
  })), [drivers]);

  const laneMap = useMemo(() => DISPATCH_STATUSES.reduce((acc, s) => {
    const srv = ops?.lanes?.find(l => l.status === s);
    acc[s] = { count: srv?.count ?? 0, items: srv?.items ?? [] };
    return acc;
  }, {} as Record<DeliveryStatus, { count: number; items: AdminOpsOverview['lanes'][number]['items'] }>), [ops]);

  const needsAttention = useMemo(() => {
    if (!ops?.exceptions) return [];
    const rank = (e: { slaHealth?: string; slaWorstHealth?: string; severity?: string }): number => {
      const h = (e.slaHealth && e.slaHealth !== 'NONE') ? e.slaHealth : e.slaWorstHealth;
      if (h === 'BREACHED' || h === 'LATE' || e.severity === 'CRITICAL') return 0;
      if (h === 'AT_RISK' || e.severity === 'WARNING') return 1;
      return 2;
    };
    return [...ops.exceptions].sort((a, b) => rank(a) - rank(b));
  }, [ops]);

  const driverGroups = useMemo(() => ({
    online: drivers.filter(d => d.onlineStatus === 'ONLINE'),
    onBreak: drivers.filter(d => d.onlineStatus === 'ON_BREAK'),
    offline: drivers.filter(d => d.onlineStatus === 'OFFLINE' || !d.onlineStatus),
  }), [drivers]);

  return {
    refreshing, isLoading, refetch,
    stats, ops, kpi, drivers, activeRoutesCount, returns, counts,
    healthSummary, healthProblemsSummary,
    today, overdueCount, slaPercent,
    trend, deliveredDelta, failedDelta, lateDelta, slaDelta, vsPrev, deliveredSub,
    safeDrivers, laneMap, needsAttention, driverGroups,
    activeRoutes, focusedRouteId, setFocusedRouteId,
    driverName, getStatusConfig,
  };
}
