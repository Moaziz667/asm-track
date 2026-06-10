import React, { useEffect, useState, useCallback, useMemo, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { AdminOpsOverview, DashboardStats, DeliveryStatus } from '@/types';
import { useRealtimeEvent, useRealtimeStatus } from '@/components/RealtimeProvider';
import { cn } from '@/lib/utils';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { useIsDark } from '@/lib/theme';
import {
  BarChart, Bar, AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';
import {
  IconPackage, IconChartBar, IconUser, IconRoute, IconRefresh, IconArrowUpRight, 
  IconAlertTriangle, IconCheck, IconTruck, IconMapPin, IconShield,
  IconInbox, IconDots, IconArrowRight, IconTable, IconLayoutKanban, IconCalendar, IconClock,
  IconTrendingUp, IconChevronRight, IconServer, IconServerOff
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { DraggableWidgetGrid } from '@/components/layout/DraggableWidgetGrid';
import { useNavigate as useRouter } from 'react-router-dom';
import DispatchLiveMap from '@/components/DispatchLiveMap';
import ActivityTicker from '@/components/ActivityTicker';
import StatusBadge from '@/components/StatusBadge';
import { KPICard } from '@/components/ui/kpi-card';
import { SectionCard } from '@/components/ui/section-card';
import { Badge } from '@/components/ui/badge';
import { useRoutes } from '@/hooks/useRoutes';
import { getDayBucket, formatElapsed, getBusinessDayKey } from '@/lib/sla';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { dispatchDeskQueueLink } from '@/lib/dispatch-link';
import { deriveHealthSummary } from '@/lib/system-health';

const capitalize = (s: string) => s ? s.charAt(0).toUpperCase() + s.slice(1).toLowerCase() : '';

// Shared route palette — the same colour identifies a route's legend row, its stop pins and its
// driver car on the live map, so a dispatcher can match them at a glance.
const ROUTE_PALETTE = ['#5E6AD2', '#2D8A5E', '#D4772C', '#9333EA', '#0891B2', '#DB2777', '#CA8A04', '#4F46E5', '#15803D', '#B45309'];
export function routeColor(routeId?: string | null): string {
  if (!routeId) return '#71717A';
  let h = 0;
  for (let i = 0; i < routeId.length; i++) h = (h * 31 + routeId.charCodeAt(i)) >>> 0;
  return ROUTE_PALETTE[h % ROUTE_PALETTE.length];
}

// Status colors for the Kanban cards
const STATUS_COLOR_MAP: Record<DeliveryStatus, string> = {
  UNSCHEDULED:          '#C4881A',
  SCHEDULED:            '#5E6AD2',
  PICKED_UP:            '#2594B8',
  IN_TRANSIT:           '#D4772C',
  DELIVERED:            '#4CAF82',
  PARTIALLY_DELIVERED:  '#7B6FCC',
  FAILED:               '#C7372F',
  CANCELLED:            '#8A8F98',
};

// Status styles for high-density table rows
const STATUS_ROW_COLOR_MAP: Record<DeliveryStatus, { text: string; bg: string; border: string }> = {
  UNSCHEDULED:          { text: '#A06D10', bg: 'rgba(196,136,26,0.09)',  border: 'rgba(196,136,26,0.15)' },
  SCHEDULED:            { text: '#4C56B8', bg: 'rgba(94,106,210,0.09)',  border: 'rgba(94,106,210,0.15)' },
  PICKED_UP:            { text: '#1A7A9A', bg: 'rgba(37,148,184,0.09)',  border: 'rgba(37,148,184,0.15)' },
  IN_TRANSIT:           { text: '#B05A18', bg: 'rgba(212,119,44,0.09)',  border: 'rgba(212,119,44,0.15)' },
  DELIVERED:            { text: '#2D8A5E', bg: 'rgba(76,175,130,0.09)',  border: 'rgba(76,175,130,0.15)' },
  PARTIALLY_DELIVERED:  { text: '#6055A8', bg: 'rgba(123,111,204,0.09)', border: 'rgba(123,111,204,0.15)' },
  FAILED:               { text: '#A52B24', bg: 'rgba(199,55,47,0.09)',   border: 'rgba(199,55,47,0.15)' },
  CANCELLED:            { text: '#6B7280', bg: 'rgba(138,143,152,0.08)', border: 'rgba(138,143,152,0.15)' },
};

const KANBAN_GRADIENT_MAP: Record<string, string> = {
  UNSCHEDULED:          'var(--gradient-orange)',
  SCHEDULED:            'var(--gradient-purple)',
  PICKED_UP:            'var(--gradient-teal)',
  IN_TRANSIT:           'var(--gradient-blue)',
  FAILED:               'var(--gradient-fuchsia)',
  DELIVERED:            'var(--gradient-blue)',
};

const DISPATCH_STATUSES: DeliveryStatus[] = [
  'UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT', 'FAILED', 'DELIVERED',
];

// Realtime events that can move a dashboard KPI. Excludes high-frequency noise
// (driver.location_updated) so a moving truck doesn't trigger constant refetches.
const DASHBOARD_EVENTS = [
  'delivery.created', 'delivery.scheduled', 'delivery.completed', 'delivery.failed',
  'delivery.cancelled', 'delivery.in_transit', 'delivery.reassigned', 'sla.breach',
  'erp.orders_ready', 'route.validated',
] as const;

export default function DashboardPage() {
  const t = useT();
  const navigate = useRouter();
  const isDark = useIsDark();
  const [period, setPeriod] = useState<'day' | 'week' | 'month' | 'all'>('all');
  const [viewMode, setViewMode] = useState<'office' | 'kanban'>('office');
  const queryClient = useQueryClient();
  const connected = useRealtimeStatus();

  // Source of truth = REST via React Query. Realtime events only invalidate this
  // (debounced), so KPIs self-heal from the server instead of trusting WS payloads.
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
        drivers: (Array.isArray(driversData) ? driversData : (driversData?.content ?? driversData?.drivers ?? [])) as any[],
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

  const healthSummary = useMemo(() => {
    return deriveHealthSummary(healthData);
  }, [healthData]);

  const healthProblemsSummary = useMemo(() => {
    if (!healthData) return '';
    const downCbs = (healthData.circuitBreakers ?? []).filter(
      (cb: any) => cb.state === 'OPEN' || cb.state === 'FORCED_OPEN' || cb.reachable === false
    );
    const parts: string[] = [];
    
    if (downCbs.length > 0) {
      const names = Array.from(
        new Set(
          downCbs.map((cb: any) => {
            const n = cb.name.toLowerCase();
            if (n.includes('erp') || n.includes('odoo')) return t.dashboardPage.systemHealthCategoryErp || 'ERP';
            if (n.includes('keycloak') || n.includes('auth')) return t.dashboardPage.systemHealthCategoryAuth || 'Auth';
            if (n.includes('route') || n.includes('osrm') || n.includes('geocode')) return t.dashboardPage.systemHealthCategoryRoutes || 'Carto';
            if (n.includes('driver')) return t.dashboardPage.systemHealthCategoryDrivers || 'Chauffeurs';
            return cb.name;
          })
        )
      );
      parts.push(`${names.join(', ')}`);
    }
    
    if (healthData.db?.reachable === false) {
      parts.push(t.dashboardPage.systemHealthDb || 'Base de données');
    }
    
    const stuckQueuesCount = Object.entries(healthData.dlq ?? {}).filter(([, v]) => Number(v) > 0).length;
    if (stuckQueuesCount > 0) {
      parts.push(t.dashboardPage.systemHealthDlq?.replace('{count}', String(stuckQueuesCount)) || `${stuckQueuesCount} file(s) DLQ`);
    }
    
    if ((healthData.erpSync?.failed ?? 0) > 0) {
      parts.push(t.dashboardPage.systemHealthErpSync || 'Synchro ERP');
    }
    
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

  // Reconnect catch-up: when the socket re-establishes, refetch once to absorb
  // anything that happened while it was down.
  const prevConnected = useRef(connected);
  useEffect(() => {
    if (connected && !prevConnected.current) {
      queryClient.invalidateQueries({ queryKey: ['dashboard-overview'] });
      queryClient.invalidateQueries({ queryKey: ['routes'] });
    }
    prevConnected.current = connected;
  }, [connected, queryClient]);

  const { locale } = useLocaleStore();
  const todayIso = useMemo(() => getBusinessDayKey(), []);
  const { data: todayRoutes = [] } = useRoutes({ from: todayIso, to: todayIso });

  // Routes that are live on the map: ready-to-start (VALIDATED) + currently running (IN_PROGRESS).
  const activeRoutes = useMemo(
    () => todayRoutes.filter(r => r.status === 'VALIDATED' || r.status === 'IN_PROGRESS'),
    [todayRoutes]
  );
  // Shared focus between the Tournées legend widget and the live map (set on row/pin click).
  const [focusedRouteId, setFocusedRouteId] = useState<string | null>(null);

  const driverName = useCallback((id: string | undefined) => {
    if (!id) return 'Non assigné';
    const d = drivers.find(d => d.id === id);
    return d ? d.name : id;
  }, [drivers]);

  const getStatusConfig = (status: DeliveryStatus): { label: string; color: string } => ({
    label: t.statusLabels[status] || status,
    color: STATUS_COLOR_MAP[status],
  });

  useEffect(() => {
    // Load preferred view mode from localStorage on mount
    const cachedMode = localStorage.getItem('asm_dashboard_view');
    if (cachedMode === 'office' || cachedMode === 'kanban') {
      setViewMode(cachedMode);
    }
  }, []);

  const handleViewChange = (mode: 'office' | 'kanban') => {
    setViewMode(mode);
    localStorage.setItem('asm_dashboard_view', mode);
  };

  const today = stats?.today;
  // Unified SLA (source of truth): breached = past the window/promise; needs-attention = at-risk + breached.
  const overdueCount = (ops?.sla as any)?.slaBreached ?? 0;
  const exceptionsCount = ((ops?.sla as any)?.slaBreached ?? 0) + ((ops?.sla as any)?.slaAtRisk ?? 0);
  const slaPercent = today?.total ? Math.round((today.delivered / today.total) * 100) : 100;

  // ── Real KPI series + deltas from /reports/dashboard (30-day daily trend) ──
  const trend = useMemo(
    () => (Array.isArray(kpi?.weeklyTrend) ? kpi.weeklyTrend : []) as Array<{ count: number; delivered: number; failed: number }>,
    [kpi],
  );
  const deliveredSpark = useMemo(() => trend.map(d => Number(d.delivered) || 0), [trend]);
  const totalSpark = useMemo(() => trend.map(d => Number(d.count) || 0), [trend]);
  const completionSpark = useMemo(
    () => trend.map(d => { const c = Number(d.count) || 0; return c > 0 ? Math.round(((Number(d.delivered) || 0) / c) * 100) : 100; }),
    [trend],
  );
  // Week-over-week volume delta (last 7 days vs the 7 before).
  const deliveredDelta = useMemo(() => {
    if (deliveredSpark.length < 14) return null;
    const sum = (a: number[]) => a.reduce((x, y) => x + y, 0);
    const last = sum(deliveredSpark.slice(-7));
    const prev = sum(deliveredSpark.slice(-14, -7));
    return prev === 0 ? null : ((last - prev) / prev) * 100;
  }, [deliveredSpark]);
  // Completion-rate delta in percentage points (last 7 days vs the 7 before),
  // kept consistent with the value (slaPercent) and the completion sparkline.
  const slaDelta = useMemo(() => {
    if (completionSpark.length < 14) return null;
    const avg = (a: number[]) => (a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0);
    return avg(completionSpark.slice(-7)) - avg(completionSpark.slice(-14, -7));
  }, [completionSpark]);

  const vsPrev = t.dashboardPage.kpiVsPrevPeriod || 'vs prev. period';
  const slaSub = slaDelta == null ? undefined : `${slaDelta >= 0 ? '+' : ''}${slaDelta.toFixed(1)} pts ${vsPrev}`;
  const deliveredSub = deliveredDelta == null
    ? `/ ${today?.total ?? 0}`
    : `${deliveredDelta >= 0 ? '+' : ''}${deliveredDelta.toFixed(0)}% ${vsPrev}`;

  // Flatten active deliveries from lanes for the table
  const activeDeliveries = useMemo(() => {
    const list = ops?.lanes?.flatMap(l => l.items || []) || [];
    const unique = Array.from(new Map(list.map(item => [item.deliveryId || item.orderRef, item])).values());
    return unique.slice(0, 8).map(d => {
      const item = d as any;
      return {
        ...d,
        dropoffLat: Number(item.dropoffLat) || 36.8065,
        dropoffLng: Number(item.dropoffLng) || 10.1815,
        status: item.status || 'UNSCHEDULED'
      };
    });
  }, [ops]);

  const safeDrivers = useMemo(() => {
    return drivers.map(d => ({
      ...d,
      currentLat: Number(d.currentLat) || null,
      currentLng: Number(d.currentLng) || null,
    }));
  }, [drivers]);

  // Kanban lane map calculation
  const laneMap = useMemo(() => {
    return DISPATCH_STATUSES.reduce((acc, s) => {
      const srv = ops?.lanes?.find(l => l.status === s);
      acc[s] = { count: srv?.count ?? 0, items: srv?.items ?? [] };
      return acc;
    }, {} as Record<DeliveryStatus, { count: number; items: any[] }>);
  }, [ops]);

  const needsAttention = useMemo(() => {
    if (!ops?.exceptions) return [];
    return ops.exceptions.slice(0, 8);
  }, [ops]);

  const driverGroups = useMemo(() => {
    const online = drivers.filter(d => d.onlineStatus === 'ONLINE');
    const onBreak = drivers.filter(d => d.onlineStatus === 'ON_BREAK');
    const offline = drivers.filter(d => d.onlineStatus === 'OFFLINE' || !d.onlineStatus);
    return { online, onBreak, offline };
  }, [drivers]);

  return (
    <div className="w-full flex flex-col bg-[var(--app-bg)] min-h-[calc(100vh-56px)] select-none animate-fadeIn">
      
      {/* ── HEADER PANEL ── */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0 shadow-2xs">
        <div className="px-6 py-4 flex items-center justify-between gap-6 max-w-[1800px] mx-auto">
          <div className="flex flex-col text-start">
            <h1 className="text-[13.5px] font-bold text-[var(--text-primary)] leading-tight tracking-tight">
              {t.dashboardPage?.title || 'Tableau de bord'}
            </h1>
            <div className="flex items-center gap-2 mt-1 flex-wrap">
              <span className="text-[11px] text-[var(--text-muted)] font-medium">
                {t.dashboardPage?.subtitle || 'Supervision administrative et indicateurs opérationnels'}
              </span>
              {overdueCount > 0 && (
                <button
                  onClick={() => navigate('/dispatch-desk?tab=queue')}
                  style={{
                    backgroundColor: isDark ? 'rgba(239, 68, 68, 0.15)' : '#DC2626',
                    color: isDark ? '#F87171' : '#FFFFFF',
                    borderColor: isDark ? 'rgba(239, 68, 68, 0.3)' : '#B91C1C',
                  }}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-bold border transition-all cursor-pointer shadow-2xs"
                >
                  <IconAlertTriangle size={11} className="animate-pulse" style={{ color: isDark ? '#F87171' : '#FFFFFF' }} />
                  <span>
                    {(t.dashboardPage.overdueChipLabel || '{count} non planifiées en retard')
                      .replace('{count}', String(overdueCount))
                      .replace('{plural}', overdueCount > 1 ? 's' : '')}
                  </span>
                </button>
              )}
            </div>
          </div>

          <div className="flex items-center gap-3 shrink-0">
            {/* View Mode selector (Pro Toggle) */}
            <div className="flex items-center gap-1.5 me-2">
              <button
                type="button"
                onClick={() => handleViewChange('office')}
                className={cn(
                  "px-3 py-1 text-[11px] font-bold transition-all rounded-full cursor-pointer flex items-center gap-1 h-7 active:scale-[0.95]",
                  viewMode === 'office'
                    ? "bg-background text-foreground border border-border shadow-2xs font-semibold"
                    : "text-muted-foreground hover:text-foreground bg-transparent"
                )}
              >
                <IconTable size={12} />
                {locale === 'ar' ? 'الجدول' : 'Tableau'}
              </button>
              <button
                type="button"
                onClick={() => handleViewChange('kanban')}
                className={cn(
                  "px-3 py-1 text-[11px] font-bold transition-all rounded-full cursor-pointer flex items-center gap-1 h-7 active:scale-[0.95]",
                  viewMode === 'kanban'
                    ? "bg-background text-foreground border border-border shadow-2xs font-semibold"
                    : "text-muted-foreground hover:text-foreground bg-transparent"
                )}
              >
                <IconLayoutKanban size={12} />
                Kanban
              </button>
            </div>

            {/* Period selector */}
            <div className="flex items-center gap-1 me-1">
              {(['day', 'week', 'month', 'all'] as const).map((p) => {
                const labelMap = {
                  day: t.dashboardPage.periodDay,
                  week: t.dashboardPage.periodWeek,
                  month: t.dashboardPage.periodMonth,
                  all: t.dashboardPage.periodAll,
                };
                const active = period === p;
                return (
                  <button
                    key={p}
                    type="button"
                    onClick={() => setPeriod(p)}
                    className={cn(
                      "px-3 py-1 text-[11px] font-bold transition-all rounded-full cursor-pointer h-7 flex items-center justify-center active:scale-[0.95]",
                      active
                        ? "bg-background text-foreground border border-border shadow-2xs font-semibold"
                        : "text-muted-foreground hover:text-foreground bg-transparent"
                    )}
                  >
                    {labelMap[p]}
                  </button>
                );
              })}
            </div>

            <RefreshButton refreshing={refreshing} onClick={() => refetch()} />
          </div>
        </div>
      </div>



      {/* ── MAIN VIEW CONTENT SWITCHER ── */}
      {viewMode === 'office' ? (
        /* ── OFFICE DESK LAYOUT ── */
        <div className="px-6 py-6 w-full max-w-[1800px] mx-auto flex-1 animate-fadeIn overflow-y-auto">
        <DraggableWidgetGrid
          storageKey="dashboard-v3"
          items={[
            {
              id: 'kpi-sla',
              defaultLayout: { w: 3, h: 2, x: 0, y: 0, minW: 2, minH: 2 },
              className: '',
              children: <KPICard
                  label={t.dashboardPage.slaRateLabel}
                value={`${slaPercent}%`}
                sub={slaSub}
                sparklineData={completionSpark.length > 0 ? completionSpark : undefined}
                tone={(today?.total ?? 0) > 0 ? (slaPercent >= 90 ? 'success' : slaPercent >= 70 ? 'warning' : 'danger') : 'default'}
                className="h-full"
              />
            },
            {
              id: 'kpi-delivered',
              defaultLayout: { w: 2, h: 2, x: 3, y: 0, minW: 2, minH: 2 },
              className: '',
              children: <KPICard
                label={t.dashboardPage.kpiDelivered || 'Livrés'}
                value={today?.delivered ?? 0}
                sub={deliveredSub}
                sparklineData={deliveredSpark.length > 0 ? deliveredSpark : undefined}
                tone={(today?.delivered ?? 0) > 0 ? "info" : "default"}
                className="h-full"
              />
            },
            {
              id: 'kpi-routes',
              defaultLayout: { w: 2, h: 2, x: 5, y: 0, minW: 2, minH: 2 },
              className: '',
              children: <KPICard
                label={t.dashboardPage.kpiActiveRoutes || 'Tournées'}
                value={activeRoutesCount}
                sub={t.dashboardPage.kpiActiveRoutesSub || 'en cours'}
                tone={activeRoutesCount > 0 ? "info" : "default"}
                className="h-full"
              />
            },
            {
              id: 'kpi-drivers',
              defaultLayout: { w: 2, h: 2, x: 7, y: 0, minW: 2, minH: 2 },
              className: '',
              children: <KPICard
                label={t.dashboardPage.kpiDriversOnline || 'En ligne'}
                value={driverGroups.online.length}
                sub={`/ ${drivers.length}`}
                tone={driverGroups.online.length === 0 ? "danger" : "default"}
                className="h-full"
              />
            },
            {
              id: 'kpi-system-health',
              defaultLayout: { w: 3, h: 2, x: 9, y: 0, minW: 2, minH: 2 },
              className: '',
              children: <KPICard
                label={t.dashboardPage.systemHealthLabel || 'Santé Système'}
                value={
                  healthSummary.serviceCount > 0
                    ? `${healthSummary.okServices} / ${healthSummary.serviceCount}`
                    : '--'
                }
                sub={
                  healthSummary.allGood
                    ? t.dashboardPage.systemHealthOptimal || 'Optimal'
                    : `${healthSummary.downCount > 0 ? (t.dashboardPage.systemHealthOffline || 'Hors ligne') : (t.dashboardPage.systemHealthDegraded || 'Dégradé')} · ${healthProblemsSummary}`
                }
                icon={
                  healthSummary.allGood ? (
                    <IconServer size={16} className="text-[var(--success)]" />
                  ) : (
                    <IconServerOff size={16} className={healthSummary.downCount > 0 ? "text-[var(--danger)]" : "text-[var(--warning)]"} />
                  )
                }
                tone={
                  healthSummary.allGood
                    ? 'success'
                    : healthSummary.downCount > 0
                    ? 'danger'
                    : 'warning'
                }
                onClick={() => navigate('/system-health')}
                className="h-full"
              />
            },
            {
              id: 'trend-chart',
              defaultLayout: { w: 12, h: 3, x: 0, y: 2, minW: 8, minH: 3 },
              className: '',
              children: (
                <div className="card overflow-hidden flex flex-col h-full">
                  <div className="pl-10 pr-5 py-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
                    <div className="flex items-center gap-2">
                      <IconChartBar size={16} style={{ color: 'var(--brand)' }} />
                      <span className="text-[11px] font-[600]" style={{ color: 'var(--text-primary)' }}>
                        {t.performancePage.volumeCurve}
                      </span>
                    </div>
                    <span className="text-[11px] font-medium" style={{ color: 'var(--text-muted)' }}>
                      {t.performancePage.lastSevenDays}
                    </span>
                  </div>
                  
                  <div className="p-4 flex-1 min-h-0 w-full">
                    {trend.length === 0 ? (
                      <div className="flex items-center justify-center h-full opacity-40">
                        <span className="text-[11px]">{t.dashboardPage.noData || "Aucune donnée disponible"}</span>
                      </div>
                    ) : (
                      <ResponsiveContainer width="100%" height="100%">
                        <AreaChart data={trend} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                          <defs>
                            <linearGradient id="colorVolume" x1="0" y1="0" x2="0" y2="1">
                              <stop offset="5%" stopColor="var(--brand)" stopOpacity={0.25}/>
                              <stop offset="95%" stopColor="var(--brand)" stopOpacity={0}/>
                            </linearGradient>
                          </defs>
                          <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" />
                          <XAxis
                            dataKey="date"
                            tickFormatter={(v) => v ? v.split('-').slice(1).reverse().join('/') : ''}
                            tick={{ fontSize: 10, fontWeight: 500, fill: 'var(--text-secondary)' }}
                            axisLine={false}
                            tickLine={false}
                          />
                          <YAxis
                            tick={{ fontSize: 10, fontWeight: 500, fill: 'var(--text-secondary)' }}
                            axisLine={false}
                            tickLine={false}
                          />
                          <Tooltip
                            cursor={{ stroke: 'var(--border)', strokeWidth: 1 }}
                            contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '4px', padding: '8px 12px', color: 'var(--text-primary)', fontSize: 11 }}
                            labelStyle={{ color: 'var(--text-secondary)', fontSize: '10px', fontWeight: 500, marginBottom: '4px' }}
                            itemStyle={{ color: 'var(--text-primary)', fontSize: '12px', fontWeight: 600, fontFamily: 'monospace' }}
                            formatter={(value) => [`${value}`, t.performancePage.volume || 'Volume']}
                          />
                          <Area 
                            type="monotone" 
                            dataKey="count" 
                            stroke="var(--brand)" 
                            strokeWidth={2}
                            fillOpacity={1} 
                            fill="url(#colorVolume)"
                            activeDot={{ r: 4, strokeWidth: 0, fill: 'var(--brand)' }}
                          />
                        </AreaChart>
                      </ResponsiveContainer>
                    )}
                  </div>
                </div>
              ),
            },
            {
              id: 'dispatch-live-map',
              defaultLayout: { w: 8, h: 8, x: 0, y: 4, minW: 6, minH: 6 },
              className: '',
              children: (
                <div className="card h-full overflow-hidden flex flex-col relative">
                  <DispatchLiveMap
                    activeStops={activeDeliveries as any}
                    drivers={safeDrivers as any}
                  />
                </div>
              ),
            },
            ...(needsAttention.length > 0 ? [{
              id: 'needs-attention',
              defaultLayout: { w: 4, h: 8, x: 8, y: 4, minW: 3, minH: 6 },
              className: '',
              children: (
                <div className="flex flex-col bg-[#FEF2F2] dark:bg-[#C7372F]/10 border border-[#C7372F]/30 rounded-[12px] h-full shadow-sm overflow-hidden relative">
                  <div className="absolute top-0 left-0 right-0 h-[3px] bg-[#C7372F]" />
                  <div className="ps-8 pe-4 py-3 border-b border-[var(--border)] flex items-center justify-between shrink-0">
                    <span className="text-[16px] font-bold text-[var(--text-primary)]">{t.dashboardPage.needsAttention || "Needs Attention"}</span>
                    <button
                      onClick={() => navigate('/dispatch-desk?tab=queue')}
                      className="text-[11px] font-medium text-[var(--brand-blue)] hover:underline flex items-center gap-1 cursor-pointer transition-colors"
                    >
                      {t.dashboardPage.needsAttentionViewAll || "View all"} <IconArrowUpRight size={11} />
                    </button>
                  </div>
                  <div className="flex-1 overflow-y-auto px-6 py-2" style={{ scrollbarWidth: 'thin' }}>
                    <div className="flex flex-col gap-3 py-2">
                      {needsAttention.map((exc: any, idx: number) => {
                        const isCrit = exc.slaHealth === 'BREACHED' || exc.severity === 'CRITICAL';
                        const accent = isCrit ? '#C7372F' : '#D4772C';
                        const timeRef = exc.scheduledAt || exc.createdAt;
                        const timeStr = timeRef ? formatElapsed(timeRef, locale) : '—';

                        return (
                          <div
                            key={idx}
                            onClick={() => navigate(dispatchDeskQueueLink({ orderRef: exc.orderRef, orderId: exc.orderId, deliveryId: exc.deliveryId }))}
                            className="flex items-stretch rounded-[10px] border bg-[var(--surface)] hover:shadow-sm transition-all cursor-pointer overflow-hidden group"
                            style={{ borderColor: 'var(--border)' }}
                          >
                            <span className="w-[3px] shrink-0" style={{ background: accent }} />
                            <div className="flex flex-col min-w-0 flex-1 gap-1.5 p-2.5">
                              <div className="flex items-center justify-between gap-2">
                                <span className="font-mono text-[11px] font-bold" style={{ color: 'var(--brand)' }}>
                                  {exc.orderRef || exc.deliveryId?.slice(0, 8) || 'Alert'}
                                </span>
                                <span className="text-[10px] font-mono text-[var(--text-soft)] shrink-0">{timeStr}</span>
                              </div>
                              <span className="text-[13px] font-semibold text-[var(--text-primary)] truncate leading-tight">
                                {exc.clientName || '—'}{exc.city ? <span className="font-normal text-[var(--text-muted)]"> · {exc.city}</span> : null}
                              </span>
                              <div className="flex items-center gap-1.5 flex-wrap">
                                <StatusBadge status={exc.status} size="sm" />
                                <SlaHealthBadge health={exc.slaHealth} />
                                {exc.driverName && (
                                  <span className="text-[10.5px] text-[var(--text-muted)] truncate">· {exc.driverName}</span>
                                )}
                              </div>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                </div>
              ),
            }] : []),
            {
              id: 'quick-actions',
              defaultLayout: { w: 6, h: 5, x: 0, y: 12, minW: 4, minH: 4 },
              className: '',
              children: (
                <div className="card p-4 h-full flex flex-col">
                  <span className="text-[14px] font-bold text-[var(--text-primary)] block mb-3 pl-6 shrink-0">
                    {t.dashboardPage.quickActions || 'Quick Actions'}
                  </span>
                  <div className="grid grid-cols-2 gap-3 flex-1 min-h-0">
                    {[
                      { label: t.dashboardPage.actionGoToDispatch || 'Dispatch Desk', path: '/dispatch-desk', Icon: IconLayoutKanban },
                      { label: t.dashboardPage.actionGoToPlanner || 'Route Builder', path: '/route-builder', Icon: IconRoute },
                      { label: t.dashboardPage.actionGoToRoutes || 'Routes Table', path: '/routes-table', Icon: IconMapPin },
                      { label: t.dashboardPage.actionGoToDeliveries || 'Deliveries Log', path: '/deliveries', Icon: IconPackage },
                    ].map(({ label, path, Icon }) => (
                      <button
                        key={path}
                        type="button"
                        onClick={() => navigate(path)}
                        className="flex items-center gap-3 px-4 py-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] hover:border-[var(--brand-blue)]/30 transition-all cursor-pointer text-left active:scale-[0.98] group"
                      >
                        <Icon size={18} className="text-[var(--text-muted)] group-hover:text-[var(--brand-blue)] shrink-0 transition-colors" strokeWidth={1.8} />
                        <span className="text-[13px] font-medium text-[var(--text-secondary)] group-hover:text-[var(--brand-blue)] leading-tight transition-colors">{label}</span>
                      </button>
                    ))}
                  </div>
                </div>
              ),
            },
            {
              id: 'driver-availability',
              defaultLayout: { w: 6, h: 5, x: 6, y: 12, minW: 4, minH: 4 },
              className: '',
              children: (
                <div className="card p-4 h-full flex flex-col">
                  <span className="text-[14px] font-bold text-[var(--text-primary)] block mb-3 pl-6 shrink-0">{t.dashboardPage.driverAvailability || "Fleet Status"}</span>
                  <div className="flex flex-col gap-3 overflow-y-auto pl-2">
                    {[
                      { group: driverGroups.online, label: "Online", dotColor: '#4CAF82' },
                      { group: driverGroups.onBreak, label: "On Break", dotColor: '#D4772C' },
                      { group: driverGroups.offline, label: "Offline", dotColor: '#8A8F98' },
                    ].map(({ group, label, dotColor }) => (
                      <div key={label} className="flex items-start gap-3">
                        <span className="w-2.5 h-2.5 rounded-full shrink-0 mt-1" style={{ backgroundColor: dotColor }} />
                        <div className="flex flex-col min-w-0 flex-1">
                          <span className="text-[13px] font-bold text-[var(--text-secondary)]">
                            {label} <span className="font-mono text-[var(--text-muted)] ml-1">({group.length})</span>
                          </span>
                          {group.length > 0 && (
                            <div className="flex flex-wrap gap-1.5 mt-2">
                              {group.slice(0, 8).map((d: any, i: number) => (
                                <span key={i} className="text-[11px] font-medium px-2 py-1 rounded bg-[var(--hover-bg)] text-[var(--text-secondary)] border border-[var(--border)] truncate max-w-[100px]">
                                  {d.name || d.driverName || '?'}
                                </span>
                              ))}
                              {group.length > 8 && (
                                <span className="text-[11px] font-bold text-[var(--text-soft)] px-1 py-1">+{group.length - 8}</span>
                              )}
                            </div>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ),
            },
            {
              id: 'section-start',
              defaultLayout: { w: 4, h: 6, x: 8, y: 12, minW: 3, minH: 4 },
              className: '',
              children: (
                <SectionCard
                  title={
                    <div className="flex items-center gap-2">
                      <span>{t.dashboardPage?.sectionActiveRoutes || "Tournées actives"}</span>
                    </div>
                  }
                  actions={<Badge variant="secondary">{activeRoutes.length}</Badge>}
                >
                  {activeRoutes.length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-8 gap-2 opacity-40">
                      <IconRoute size={24} stroke={1.5} className="text-[var(--text-muted)]" />
                      <p className="text-[11px] font-[500] text-[var(--text-muted)]">{t.dashboardPage?.noRoutesWaiting || "Aucune tournée active"}</p>
                    </div>
                  ) : (
                    <div className="flex flex-col gap-1 pr-1">
                      {activeRoutes.map(route => {
                        const color = routeColor(route.id);
                        const focused = focusedRouteId === route.id;
                        const stopCount = route.totalStops ?? route.stops?.length ?? 0;
                        return (
                          <button
                            key={route.id}
                            type="button"
                            onClick={() => setFocusedRouteId(focused ? null : route.id)}
                            className="flex items-center gap-2.5 py-2 px-2 rounded-md text-left transition-colors"
                            style={{ background: focused ? 'var(--hover-bg)' : 'transparent', boxShadow: focused ? `inset 2px 0 0 ${color}` : undefined }}
                            title={t.dashboardPage?.focusOnMap || 'Centrer sur la carte'}
                          >
                            <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ background: color }} />
                            <div className="min-w-0 flex-1">
                              <div className="flex items-center gap-1.5">
                                <p className="text-[12px] font-bold text-[var(--text-primary)] truncate">{route.name}</p>
                                <StatusBadge status={route.status} size="sm" />
                              </div>
                              <p className="text-[11px] text-[var(--text-muted)] truncate">
                                {driverName(route.driverId)} · {stopCount} {t.dashboardPage?.stopsLabel || 'arrêts'}
                              </p>
                            </div>
                            <span
                              role="link"
                              onClick={(e) => { e.stopPropagation(); window.open(`/routes/${route.id}`, '_blank'); }}
                              className="shrink-0 p-1 -m-1 rounded hover:bg-[var(--hover-bg)]"
                              title={t.tooltips?.viewDetail || 'Ouvrir'}
                            >
                              <IconChevronRight size={14} className="text-[var(--text-muted)]" />
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  )}
                </SectionCard>
              )
            },
            {
              id: 'activity-ticker',
              defaultLayout: { w: 4, h: 6, x: 0, y: 18, minW: 3, minH: 4 },
              className: '',
              children: <ActivityTicker />,
            },
          ]}
        />
        </div>
      ) : (
        /* ── KANBAN VIEW ── */
        <div className="px-6 pb-6 w-full max-w-[1800px] mx-auto flex flex-col flex-1 min-h-0 overflow-hidden animate-fadeIn">
          <div className="pt-4 pb-2 flex items-center justify-between">
            <span className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              {t.dashboardPage.dispatchFlowTitle || 'Flux de Dispatch'}
            </span>
            <button
              type="button"
              onClick={() => window.open('/route-builder', '_blank')}
              className="group flex items-center gap-1 text-[11.5px] font-bold text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors cursor-pointer"
            >
              {t.dashboardPage.plannerButton || 'Planificateur'} <IconArrowRight size={12} className="group-hover:translate-x-0.5 transition-transform" />
            </button>
          </div>

          {/* Kanban board columns container */}
          <div className="overflow-x-auto border border-[var(--border)] rounded-lg bg-[var(--surface)] shadow-2xs">
            <div
              className="flex flex-nowrap items-stretch"
              style={{ height: 'calc(100vh - 180px)', minHeight: 580 }}
            >
              {DISPATCH_STATUSES.map(status => {
                const config = getStatusConfig(status);
                const data = laneMap[status];
                const items = data?.items ?? [];
                const gradient = KANBAN_GRADIENT_MAP[status] || 'var(--gradient-blue)';

                return (
                  <div
                    key={status}
                    className="border-r border-[var(--border)] last:border-r-0 flex flex-col bg-[var(--app-bg)]/35"
                    style={{ width: 268, flexShrink: 0, height: '100%' }}
                  >
                    {/* Top Accent Gradient Border */}
                    <div style={{ height: 3, background: gradient, width: '100%' }} />

                    {/* Column Header */}
                    <div className="flex-none px-3 py-2.5 flex items-center justify-between border-b border-[var(--border)] bg-[var(--surface)]">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <span
                          className="px-2 py-0.5 rounded-full text-[10px] font-bold leading-none tracking-tight"
                          style={{
                            backgroundColor: `${config.color}15`,
                            color: config.color,
                          }}
                        >
                          {config.label}
                        </span>
                        <span className="text-[10.5px] font-mono font-bold text-[var(--text-soft)]">
                          {data.count}
                        </span>
                      </div>
                      <button
                        type="button"
                        className="w-5 h-5 flex items-center justify-center rounded text-[var(--text-soft)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer"
                      >
                        <IconDots size={13} />
                      </button>
                    </div>

                    {/* Cards container */}
                    <div className="flex-1 overflow-y-auto p-2.5 flex flex-col gap-2" style={{ scrollbarWidth: 'thin' }}>
                      {items.length === 0 ? (
                        <div className="flex flex-col items-center justify-center flex-1 py-12 gap-2 opacity-45">
                          <IconInbox size={18} stroke={1.2} className="text-[var(--text-soft)]" />
                          <span className="text-[10px] font-bold text-[var(--text-soft)]">{t.dashboardPage.emptyState || 'Vide'}</span>
                        </div>
                      ) : (
                        items.map((d: any, idx: number) => {
                          const isLot = d.isLot || d.deliveriesCount > 1 || d.orderRef?.startsWith('LOT');
                          return isLot
                            ? <LotCard key={idx} d={d} status={status} color={config.color} />
                            : <DeliveryCard key={idx} d={d} status={status} color={config.color} />;
                        })
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}

    </div>
  );
}

export function KpiCard({ title, value, subtitle, Icon, color, trend }: { title: string; value: string | number; subtitle: string; Icon: any; color: string; trend?: string }) {
  return (
    <div className="card p-4">
      <div className="flex items-start justify-between mb-3">
        <span className="text-[12px] font-medium text-[var(--text-secondary)]">{title}</span>
        <Icon size={16} strokeWidth={1.5} className="text-[var(--text-secondary)]" />
      </div>
      <div className="font-mono text-[28px] font-semibold leading-none tabular-nums text-[var(--text-primary)]">{value}</div>
      <div className="text-[11px] text-[var(--text-soft)] mt-1.5 font-normal">{subtitle}</div>
    </div>
  );
}

// ── Single delivery card ──
function DeliveryCard({ d, status, color }: { d: any; status: DeliveryStatus; color: string }) {
  const t = useT();
  const handleClick = () => {
    let url = '';
    if (status === 'UNSCHEDULED') url = '/route-builder';
    else if (status === 'DELIVERED' || status === 'PARTIALLY_DELIVERED') url = `/deliveries/${d.deliveryId}`;
    else if (d.routeId) url = `/routes/${d.routeId}`;
    else url = `/deliveries/${d.deliveryId}`;
    window.open(url, '_blank');
  };

  const routeLabel = d.routeName || d.routeRef || (d.routeId ? `Route #${d.routeId.slice(0, 5)}` : null);

  const formatCardDate = (dateStr?: string) => {
    if (!dateStr) return '';
    try {
      const date = new Date(dateStr);
      return date.toLocaleDateString(undefined, { day: '2-digit', month: 'short' }) + ' ' + 
             date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false });
    } catch {
      return dateStr.slice(5, 16).replace('T', ' ');
    }
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className="w-full text-left p-3.5 rounded-lg bg-[var(--surface)] cursor-pointer focus:outline-none transition-all hover:shadow-sm hover:brightness-[0.98] dispatch-card active:scale-[0.99]"
      style={{
        border: '1px solid var(--border)',
        borderLeft: `4px solid ${color}`,
      }}
    >
      {/* Card Header: Order ref + Creation Time */}
      <div className="mb-2 flex items-center justify-between border-b border-[var(--border)]/30 pb-1.5">
        <span className="font-mono text-[10px] font-bold tracking-tight" style={{ color }}>
          {d.orderRef || d.deliveryId?.slice(0, 8)}
        </span>
        {d.scheduledAt && (
          <span className="flex items-center gap-1 text-[9.5px] font-bold text-[var(--text-soft)]">
            <IconCalendar size={10} stroke={2.5} />
            <span>{formatCardDate(d.scheduledAt)}</span>
          </span>
        )}
      </div>

      {/* Main Client info */}
      <p className="text-[13px] font-bold text-[var(--text-primary)] leading-snug mb-1.5 truncate">
        {d.clientName || t.dashboardPage.unknownClient}
      </p>

      {/* Location / City Details */}
      {d.city && (
        <div className="text-[10.5px] text-[var(--text-soft)] font-semibold mb-2.5 flex items-center gap-1">
          <IconMapPin size={11} stroke={2.5} className="text-primary shrink-0" />
          <span className="truncate">{d.city}</span>
        </div>
      )}

      {/* SLA Status Indicator */}
      {(() => {
        if (!d.scheduledAt || !['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT'].includes(status)) return null;
        const bucket = getDayBucket(d.scheduledAt);

        if (bucket === 'overdue') {
          return (
            <div className="mb-2">
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded border border-[#fecaca] bg-[#fef2f2] text-[#b91c1c] inline-flex items-center gap-1">
                <IconClock size={11} stroke={2.5} /> En retard (Planifié)
              </span>
            </div>
          );
        } else if (bucket === 'today') {
          return (
            <div className="mb-2">
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded border border-[#fef08a] bg-[#fffbeb] text-[#b45309] inline-flex items-center gap-1">
                <IconClock size={11} stroke={2.5} /> Planifié Auj.
              </span>
            </div>
          );
        }
        return null;
      })()}

      {/* Metadata Chips */}
      <div className="flex flex-wrap gap-1.5 pt-0.5">
        {d.driverName && (
          <Chip icon={<IconUser size={10.5} stroke={2.5} />} label={d.driverName} />
        )}
        {routeLabel && (
          <Chip icon={<IconRoute size={10.5} stroke={2.5} />} label={routeLabel} />
        )}
      </div>
    </button>
  );
}

// ── Lot (multi-delivery) card ──
function LotCard({ d, status, color }: { d: any; status: DeliveryStatus; color: string }) {
  const t = useT();
  const deliveries = d.subDeliveries || [];
  const totalCount = d.deliveriesCount || deliveries.length;
  const completedCount = deliveries.filter((x: any) => x.status === 'DELIVERED').length;
  const progress = totalCount > 0 ? (completedCount / totalCount) * 100 : 0;

  const handleClick = () => {
    const url = status === 'UNSCHEDULED' ? '/route-builder' : `/deliveries?lot=${d.orderRef}`;
    window.open(url, '_blank');
  };

  const formatCardDate = (dateStr?: string) => {
    if (!dateStr) return '';
    try {
      const date = new Date(dateStr);
      return date.toLocaleDateString(undefined, { day: '2-digit', month: 'short' }) + ' ' + 
             date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false });
    } catch {
      return dateStr.slice(5, 16).replace('T', ' ');
    }
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className="w-full text-left p-3.5 rounded-lg bg-[var(--surface)] cursor-pointer focus:outline-none transition-all hover:shadow-sm hover:brightness-[0.98] dispatch-card active:scale-[0.99]"
      style={{
        border: '1px solid var(--border)',
        borderLeft: `4px solid ${color}`,
      }}
    >
      {/* Header */}
      <div className="mb-2 flex items-center justify-between border-b border-[var(--border)]/30 pb-1.5">
        <div className="flex items-center gap-1">
          <IconPackage size={10.5} style={{ color }} stroke={2.5} />
          <span className="font-mono text-[10px] font-bold tracking-tight" style={{ color }}>
            {d.orderRef || 'LOT'}
          </span>
        </div>
        {d.scheduledAt && (
          <span className="flex items-center gap-1 text-[9.5px] font-bold text-[var(--text-soft)]">
            <IconCalendar size={10} stroke={2.5} />
            <span>{formatCardDate(d.scheduledAt)}</span>
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1 mb-2.5 min-w-0">
        {deliveries.slice(0, 2).map((x: any, i: number) => (
          <p key={i} className="text-[13px] font-bold text-[var(--text-primary)] leading-snug truncate">
            {x.clientName}
          </p>
        ))}
        {deliveries.length > 2 && (
          <p className="text-[10.5px] font-bold text-[var(--text-soft)] mt-0.5">
            {t.dashboardPage.moreDeliveries.replace('{count}', String(deliveries.length - 2))}
          </p>
        )}
      </div>

      {/* SLA Status Indicator */}
      {(() => {
        if (!d.scheduledAt || !['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT'].includes(status)) return null;
        const bucket = getDayBucket(d.scheduledAt);

        if (bucket === 'overdue') {
          return (
            <div className="mb-2">
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded border border-[#fecaca] bg-[#fef2f2] text-[#b91c1c] inline-flex items-center gap-1">
                <IconClock size={11} stroke={2.5} /> En retard (Planifié)
              </span>
            </div>
          );
        } else if (bucket === 'today') {
          return (
            <div className="mb-2">
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded border border-[#fef08a] bg-[#fffbeb] text-[#b45309] inline-flex items-center gap-1">
                <IconClock size={11} stroke={2.5} /> Planifié Auj.
              </span>
            </div>
          );
        }
        return null;
      })()}

      <div className="flex items-center gap-2.5">
        <div className="flex-1 h-1 bg-[var(--border)]/65 rounded-full overflow-hidden">
          <div
             className="h-full transition-all duration-500 rounded-full"
              style={{ width: `${progress}%`, backgroundColor: color }}
          />
        </div>
        <span className="text-[10px] font-bold font-mono tabular-nums shrink-0" style={{ color }}>
          {completedCount}/{totalCount}
        </span>
      </div>
    </button>
  );
}

// ── Metadata chip ──
function Chip({ icon, label, muted }: { icon: React.ReactNode; label: string; muted?: boolean }) {
  return (
    <span
      className="inline-flex items-center gap-1.5 text-[10.5px] font-bold px-2 py-0.5 rounded-[4px] border border-[var(--border)] bg-[var(--hover-bg)] leading-none select-none transition-all"
      style={{ color: muted ? 'var(--text-soft)' : 'var(--text-muted)' }}
    >
      {icon}
      <span className="truncate max-w-[105px]">{label}</span>
    </span>
  );
}
