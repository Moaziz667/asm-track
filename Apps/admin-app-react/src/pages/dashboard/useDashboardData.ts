import { useCallback, useEffect, useMemo, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { AdminOpsOverview, DashboardStats, DeliveryStatus, Driver } from '@/types';
import { useRealtimeEvent, useRealtimeStatus } from '@/components/RealtimeProvider';
import { useT } from '@/lib/LocaleContext';
import { useRoutes } from '@/hooks/useRoutes';
import { getBusinessDayKey } from '@/lib/sla';
import { deriveHealthSummary } from '@/lib/system-health';
import { useGlobalMapStore } from '@/lib/global-map-store';
import { DISPATCH_STATUSES, DASHBOARD_EVENTS, STATUS_TONE_MAP } from './constants';

type Period = 'day' | 'week' | 'month' | 'all';

/** All data + derived metrics for the dashboard. Extracted from DashboardPage so the page is a
 *  pure layout/orchestrator. Source of truth = REST via React Query; realtime events only
 *  invalidate (debounced), so KPIs self-heal from the server. */
export function useDashboardData(period: Period) {
  const t = useT();
  const queryClient = useQueryClient();
  const connected = useRealtimeStatus();

  const { data: dash, isFetching: refreshing, refetch } = useQuery({
    queryKey: ['dashboard-overview', period],
    queryFn: async () => {
      const [sR, oR, driversRes, routesRes, kR, healthRes] = await Promise.all([
        api.get('/api/admin/deliveries/stats', { params: { period } }),
        api.get('/api/admin/ops/overview', { params: { period, limit: 1000 } }),
        api.get('/api/admin/fleet/drivers').catch(() => ({ data: [] })),
        api.get('/api/admin/routes', { params: { status: 'IN_PROGRESS' } }).catch(() => ({ data: [] })),
        api.get('/api/admin/reports/dashboard', { params: { period } }).catch(() => ({ data: null })),
        api.get('/api/admin/system/health').catch(() => ({ data: null })),
      ]);
      const driversData = driversRes.data;
      return {
        stats: (sR.data ?? null) as DashboardStats | null,
        ops: (oR.data ?? null) as AdminOpsOverview | null,
        kpi: kR.data ?? null,
        drivers: (Array.isArray(driversData) ? driversData : (driversData?.content ?? driversData?.drivers ?? [])) as Driver[],
        activeRoutesCount: Array.isArray(routesRes.data) ? routesRes.data.length : 0,
        health: healthRes.data,
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

  // Reconnect catch-up: refetch once when the socket re-establishes.
  const prevConnected = useRef(connected);
  useEffect(() => {
    if (connected && !prevConnected.current) {
      queryClient.invalidateQueries({ queryKey: ['dashboard-overview'] });
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    }
    prevConnected.current = connected;
  }, [connected, queryClient]);

  const todayIso = useMemo(() => getBusinessDayKey(), []);
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
  const overdueCount = ops?.sla?.totalBreaches ?? 0;
  const slaPercent = today?.total ? Math.round((today.delivered / today.total) * 100) : 100;

  const trend = useMemo(
    () => (Array.isArray(kpi?.weeklyTrend) ? kpi.weeklyTrend : []) as Array<{ count: number; delivered: number; failed: number }>,
    [kpi],
  );
  const deliveredSpark = useMemo(() => trend.map(d => Number(d.delivered) || 0), [trend]);
  const completionSpark = useMemo(
    () => trend.map(d => { const c = Number(d.count) || 0; return c > 0 ? Math.round(((Number(d.delivered) || 0) / c) * 100) : 100; }),
    [trend],
  );
  const deliveredDelta = useMemo(() => {
    if (deliveredSpark.length < 14) return null;
    const sum = (a: number[]) => a.reduce((x, y) => x + y, 0);
    const last = sum(deliveredSpark.slice(-7));
    const prev = sum(deliveredSpark.slice(-14, -7));
    return prev === 0 ? null : ((last - prev) / prev) * 100;
  }, [deliveredSpark]);
  const slaDelta = useMemo(() => {
    if (completionSpark.length < 14) return null;
    const avg = (a: number[]) => (a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0);
    return avg(completionSpark.slice(-7)) - avg(completionSpark.slice(-14, -7));
  }, [completionSpark]);

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
    refreshing, refetch,
    stats, ops, kpi, drivers, activeRoutesCount,
    healthSummary, healthProblemsSummary,
    today, overdueCount, slaPercent,
    trend, deliveredSpark, completionSpark, deliveredDelta, slaDelta, vsPrev, deliveredSub,
    safeDrivers, laneMap, needsAttention, driverGroups,
    activeRoutes, focusedRouteId, setFocusedRouteId,
    driverName, getStatusConfig,
  };
}
