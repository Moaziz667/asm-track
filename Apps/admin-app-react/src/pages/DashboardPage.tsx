import React, { useEffect, useState, useCallback, useMemo } from 'react';
import { api } from '@/lib/api';
import { AdminOpsOverview, DashboardStats, DeliveryStatus } from '@/types';
import { showErrorToast } from '@/lib/toast-service';
import { useNotificationsState } from '@/components/AlertsProvider';
import { cn } from '@/lib/utils';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';
import {
  IconPackage, IconChartBar, IconUser, IconRoute, IconRefresh, IconArrowUpRight, 
  IconAlertTriangle, IconCheck, IconTruck, IconMapPin, IconShield,
  IconInbox, IconDots, IconArrowRight, IconTable, IconLayoutKanban, IconCalendar, IconClock
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { DraggableWidgetGrid } from '@/components/layout/DraggableWidgetGrid';
import { useNavigate as useRouter } from 'react-router-dom';

const capitalize = (s: string) => s ? s.charAt(0).toUpperCase() + s.slice(1).toLowerCase() : '';

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

export default function DashboardPage() {
  const t = useT();
  const navigate = useRouter();
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [ops, setOps] = useState<AdminOpsOverview | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [period, setPeriod] = useState<'day' | 'week' | 'month' | 'all'>('all');
  const [viewMode, setViewMode] = useState<'office' | 'kanban'>('office');
  const [drivers, setDrivers] = useState<any[]>([]);
  const [activeRoutesCount, setActiveRoutesCount] = useState(0);
  // useNotificationsState — reads count only, does NOT re-render on action context updates
  const { notifications: ctxAlerts } = useNotificationsState();
  const { locale } = useLocaleStore();

  const getStatusConfig = (status: DeliveryStatus): { label: string; color: string } => ({
    label: t.statusLabels[status] || status,
    color: STATUS_COLOR_MAP[status],
  });

  const fetchData = useCallback(async (silent = false) => {
    if (!silent) setRefreshing(true);
    try {
      const [sR, oR, driversRes, routesRes] = await Promise.all([
        api.get('/api/admin/deliveries/stats', { params: { period } }),
        api.get('/api/admin/ops/overview', { params: { period, limit: 1000 } }),
        api.get('/api/admin/fleet/drivers').catch(() => ({ data: [] })),
        api.get('/api/admin/routes', { params: { status: 'IN_PROGRESS' } }).catch(() => ({ data: [] })),
      ]);
      setStats(sR.data);
      setOps(oR.data ?? null);
      const driversData = driversRes.data;
      setDrivers(Array.isArray(driversData) ? driversData : (driversData?.content ?? driversData?.drivers ?? []));
      setActiveRoutesCount(Array.isArray(routesRes.data) ? routesRes.data.length : 0);
    } catch {
      if (!silent) showErrorToast(null, t.dashboardPage.syncError);
    } finally {
      setRefreshing(false);
    }
  }, [period, t]);

  useEffect(() => {
    fetchData();
    // Load preferred view mode from localStorage on mount
    const cachedMode = localStorage.getItem('asm_dashboard_view');
    if (cachedMode === 'office' || cachedMode === 'kanban') {
      setViewMode(cachedMode);
    }
  }, [fetchData]);

  const handleViewChange = (mode: 'office' | 'kanban') => {
    setViewMode(mode);
    localStorage.setItem('asm_dashboard_view', mode);
  };

  const today = stats?.today;
  const overdueCount = useMemo(() => {
    if (!ops?.lanes) return 0;
    const now = new Date();
    const todayStr = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().split('T')[0];
    let count = 0;
    
    ops.lanes.forEach(lane => {
      if (lane.items) {
        lane.items.forEach((item: any) => {
          const isPending = !['DELIVERED', 'PARTIALLY_DELIVERED', 'FAILED', 'CANCELLED'].includes(item.status);
          const scheduledDate = item.scheduledAt ? item.scheduledAt.split('T')[0] : null;
          if (isPending && scheduledDate && scheduledDate < todayStr) {
            count++;
          }
        });
      }
    });
    return count;
  }, [ops]);

  const exceptionsCount = (ops?.sla?.totalBreaches ?? 0) + (ctxAlerts?.length ?? 0);
  const slaPercent = today?.total ? Math.round((today.delivered / today.total) * 100) : 100;

  // Flatten active deliveries from lanes for the table
  const activeDeliveries = useMemo(() => {
    const list = ops?.lanes?.flatMap(l => l.items || []) || [];
    const unique = Array.from(new Map(list.map(item => [item.deliveryId || item.orderRef, item])).values());
    return unique.slice(0, 8);
  }, [ops]);

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
          <div className="flex flex-col text-left">
            <h1 className="text-[13.5px] font-bold text-[var(--text-primary)] leading-tight tracking-tight">
              {t.pages.dashboard.title || 'Tableau de Bord'}
            </h1>
            <span className="text-[11px] text-[var(--text-muted)] mt-1 font-medium">
              {t.pages.dashboard.subtitle || 'Supervision administrative et indicateurs opérationnels'}
            </span>
          </div>

          <div className="flex items-center gap-3 shrink-0">
            {/* View Mode selector (Pro Toggle) */}
            <div className="flex items-center gap-1.5 mr-2">
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
            <div className="flex items-center gap-1 mr-1">
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

            <RefreshButton refreshing={refreshing} onClick={() => fetchData()} />
          </div>
        </div>
      </div>



      {/* ── MAIN VIEW CONTENT SWITCHER ── */}
      {viewMode === 'office' ? (
        /* ── OFFICE DESK LAYOUT ── */
        <div className="px-6 py-6 w-full max-w-[1800px] mx-auto flex-1 animate-fadeIn overflow-y-auto">
        <DraggableWidgetGrid
          storageKey="dashboard"
          className="flex flex-col gap-6"
          items={[
            {
              id: 'metric-strip',
              className: '',
              children: (
          <div className="flex items-stretch bg-[var(--surface)] rounded-[8px] overflow-hidden" style={{ boxShadow: 'var(--shadow-card)' }}>
            <div className="flex-1 px-5 py-4">
              <div className="text-[11px] font-medium text-[var(--text-soft)] mb-1">{t.dashboardPage.kpiSlaRate}</div>
              <div className="font-mono text-[24px] font-semibold leading-none tabular-nums" style={{ color: slaPercent >= 90 ? 'var(--success)' : slaPercent >= 70 ? 'var(--warning)' : 'var(--danger)' }}>{slaPercent}%</div>
            </div>
            <div className="w-px bg-[var(--border)]" />
            <div className="flex-1 px-5 py-4">
              <div className="text-[11px] font-medium text-[var(--text-soft)] mb-1">{t.dashboardPage.kpiDelivered}</div>
              <div className="font-mono text-[24px] font-semibold leading-none tabular-nums text-[var(--text-primary)]">{today?.delivered ?? 0}<span className="text-[14px] font-normal text-[var(--text-soft)] ml-1">{t.dashboardPage.kpiDeliveredOf} {today?.total ?? 0}</span></div>
            </div>
            <div className="w-px bg-[var(--border)]" />
            <div className="flex-1 px-5 py-4">
              <div className="text-[11px] font-medium text-[var(--text-soft)] mb-1">{t.dashboardPage.kpiActiveRoutes}</div>
              <div className="font-mono text-[24px] font-semibold leading-none tabular-nums text-[var(--text-primary)]">{activeRoutesCount}</div>
            </div>
            <div className="w-px bg-[var(--border)]" />
            <div className="flex-1 px-5 py-4">
              <div className="text-[11px] font-medium text-[var(--text-soft)] mb-1">{t.dashboardPage.kpiDriversOnline}</div>
              <div className="font-mono text-[24px] font-semibold leading-none tabular-nums text-[var(--success)]">{driverGroups.online.length}<span className="text-[14px] font-normal text-[var(--text-soft)] ml-1">/ {drivers.length}</span></div>
            </div>
          </div>

              ),
            },
            {
              id: 'progress-bar',
              className: '',
              children: (
          <div className="bg-[var(--surface)] rounded-[16px] p-5" style={{ boxShadow: 'var(--shadow-card)' }}>
            <span className="text-[13px] font-semibold text-[var(--text-primary)] block mb-4">
              {t.dashboardPage.todayProgress}
            </span>
            {(() => {
              const progressTotal = (today?.total ?? 0) || 1;
              const segments = [
                { key: 'delivered', count: today?.delivered ?? 0, color: STATUS_COLOR_MAP.DELIVERED, label: t.dashboardPage.progressDelivered },
                { key: 'inTransit', count: today?.inTransit ?? 0, color: STATUS_COLOR_MAP.IN_TRANSIT, label: t.dashboardPage.progressInTransit },
                { key: 'failed', count: today?.failed ?? 0, color: STATUS_COLOR_MAP.FAILED, label: t.dashboardPage.progressFailed },
                { key: 'pending', count: (today?.waiting ?? 0) + (today?.unscheduled ?? 0) + (today?.scheduled ?? 0), color: STATUS_COLOR_MAP.UNSCHEDULED, label: t.dashboardPage.progressPending },
              ];
              return (
                <>
                  <div className="w-full h-3 rounded-full overflow-hidden flex bg-[var(--hover-bg)]">
                    {segments.map(seg =>
                      seg.count > 0 ? (
                        <div
                          key={seg.key}
                          className="h-full transition-all duration-500"
                          style={{ width: `${(seg.count / progressTotal) * 100}%`, backgroundColor: seg.color }}
                        />
                      ) : null
                    )}
                  </div>
                  <div className="flex flex-wrap gap-4 mt-2.5">
                    {segments.map(seg => (
                      <div key={seg.key} className="flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: seg.color }} />
                        <span className="text-[10.5px] font-semibold text-[var(--text-soft)]">
                          {seg.label} <span className="font-mono font-bold">{seg.count}</span>
                        </span>
                      </div>
                    ))}
                  </div>
                </>
              );
            })()}
          </div>

              ),
            },
            {
              id: 'two-col-layout',
              className: '',
              children: (
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">

            {/* Left: Needs Attention Feed */}
            <div className="lg:col-span-2 flex flex-col bg-[var(--surface)] rounded-[16px] overflow-hidden" style={{ boxShadow: 'var(--shadow-card)', minHeight: 320 }}>
              <div className="px-5 py-3.5 border-b border-[var(--border)] flex items-center justify-between shrink-0">
                <span className="text-[13px] font-semibold text-[var(--text-primary)]">
                  {t.dashboardPage.needsAttention}
                </span>
                {needsAttention.length > 0 && (
                  <button
                    onClick={() => navigate('/dispatch-desk?tab=action')}
                    className="text-[11px] font-medium text-[#0972d3] hover:underline flex items-center gap-1 cursor-pointer transition-colors"
                  >
                    {t.dashboardPage.needsAttentionViewAll} <IconArrowUpRight size={11} />
                  </button>
                )}
              </div>
              <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin', maxHeight: 420 }}>
                {needsAttention.length === 0 ? (
                  <div className="flex flex-col items-center justify-center py-16 gap-2 opacity-50">
                    <IconCheck size={28} stroke={1.5} className="text-[#4CAF82]" />
                    <span className="text-[12px] font-medium text-[var(--text-soft)]">
                      {t.dashboardPage.needsAttentionEmpty}
                    </span>
                  </div>
                ) : (
                  <table className="w-full text-left border-collapse">
                    <thead>
                      <tr className="border-b border-[var(--border)] bg-[var(--app-bg)]/40">
                        <th className="px-4 py-2 text-[10px] font-semibold text-[var(--text-muted)] tracking-wide">Sévérité</th>
                        <th className="px-4 py-2 text-[10px] font-semibold text-[var(--text-muted)] tracking-wide">Référence</th>
                        <th className="px-4 py-2 text-[10px] font-semibold text-[var(--text-muted)] tracking-wide">Client</th>
                        <th className="px-4 py-2 text-[10px] font-semibold text-[var(--text-muted)] tracking-wide">Message</th>
                        <th className="px-4 py-2 text-[10px] font-semibold text-[var(--text-muted)] tracking-wide text-right">Heure</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-[var(--border)]/50">
                      {needsAttention.map((exc: any, idx: number) => {
                        const severity = exc.severity || 'INFO';
                        const chipColor = severity === 'CRITICAL' ? '#C7372F' : severity === 'WARNING' ? '#D4772C' : '#8A8F98';
                        const chipBg = severity === 'CRITICAL' ? 'rgba(199,55,47,0.08)' : severity === 'WARNING' ? 'rgba(212,119,44,0.08)' : 'rgba(138,143,152,0.06)';
                        return (
                          <tr
                            key={idx}
                            onClick={() => {
                              const tab = exc.type === 'FAILED' ? 'failed' : 'action';
                              const search = exc.orderRef ? `&search=${encodeURIComponent(exc.orderRef)}` : '';
                              navigate(`/dispatch-desk?tab=${tab}${search}`);
                            }}
                            className="hover:bg-[var(--hover-bg)]/60 transition-colors cursor-pointer"
                          >
                            <td className="px-4 py-2.5">
                              <span
                                className="text-[9px] font-bold px-2 py-0.5 rounded-[4px] tracking-wide"
                                style={{ backgroundColor: chipBg, color: chipColor }}
                              >
                                {severity}
                              </span>
                            </td>
                            <td className="px-4 py-2.5 font-mono text-[11px] font-semibold text-[var(--text-primary)]">
                              {exc.orderRef || '—'}
                            </td>
                            <td className="px-4 py-2.5 text-[11px] font-medium text-[var(--text-secondary)] truncate max-w-[120px]">
                              {exc.clientName || '—'}
                            </td>
                            <td className="px-4 py-2.5 text-[11px] text-[var(--text-muted)] truncate max-w-[200px]">
                              {exc.message || exc.reason || '—'}
                            </td>
                            <td className="px-4 py-2.5 text-[10px] font-mono text-[var(--text-soft)] text-right">
                              {exc.createdAt ? new Date(exc.createdAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false }) : '—'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}
              </div>
            </div>

            {/* Right Column */}
            <div className="flex flex-col gap-5">

              {/* Driver Availability Grid */}
              <div className="bg-[var(--surface)] rounded-[16px] p-5" style={{ boxShadow: 'var(--shadow-card)' }}>
                <span className="text-[13px] font-semibold text-[var(--text-primary)] block mb-3">
                  {t.dashboardPage.driverAvailability}
                </span>
                <div className="flex flex-col gap-2.5">
                  {[
                    { group: driverGroups.online, label: t.dashboardPage.driverOnline, dotColor: '#4CAF82' },
                    { group: driverGroups.onBreak, label: t.dashboardPage.driverOnBreak, dotColor: '#D4772C' },
                    { group: driverGroups.offline, label: t.dashboardPage.driverOffline, dotColor: '#8A8F98' },
                  ].map(({ group, label, dotColor }) => (
                    <div key={label} className="flex items-start gap-2">
                      <span className="w-2 h-2 rounded-full shrink-0 mt-1" style={{ backgroundColor: dotColor }} />
                      <div className="flex flex-col min-w-0">
                        <span className="text-[10.5px] font-bold text-[var(--text-secondary)]">
                          {label} <span className="font-mono text-[var(--text-muted)]">({group.length})</span>
                        </span>
                        {group.length > 0 && (
                          <div className="flex flex-wrap gap-1 mt-1">
                            {group.slice(0, 6).map((d: any, i: number) => (
                              <span key={i} className="text-[9.5px] font-semibold px-1.5 py-0.5 rounded bg-[var(--hover-bg)] text-[var(--text-muted)] border border-[var(--border)] truncate max-w-[80px]">
                                {d.name || d.driverName || '?'}
                              </span>
                            ))}
                            {group.length > 6 && (
                              <span className="text-[9px] font-bold text-[var(--text-soft)] px-1 py-0.5">
                                +{group.length - 6}
                              </span>
                            )}
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Quick Action Buttons */}
              <div className="bg-[var(--surface)] rounded-[16px] p-5" style={{ boxShadow: 'var(--shadow-card)' }}>
                <span className="text-[13px] font-semibold text-[var(--text-primary)] block mb-3">
                  {t.dashboardPage.quickActions}
                </span>
                <div className="grid grid-cols-2 gap-2.5">
                  {[
                    { label: t.dashboardPage.actionGoToDispatch, path: '/dispatch-desk', Icon: IconLayoutKanban },
                    { label: t.dashboardPage.actionGoToPlanner, path: '/route-builder', Icon: IconRoute },
                    { label: t.dashboardPage.actionGoToRoutes, path: '/routes-table', Icon: IconMapPin },
                    { label: t.dashboardPage.actionGoToDeliveries, path: '/deliveries', Icon: IconPackage },
                  ].map(({ label, path, Icon }) => (
                    <button
                      key={path}
                      type="button"
                      onClick={() => navigate(path)}
                      className="flex items-center gap-2.5 px-3.5 py-3 rounded-[12px] border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] hover:border-[var(--brand-blue)]/30 transition-all cursor-pointer text-left active:scale-[0.98] group"
                    >
                      <Icon size={16} className="text-[var(--text-muted)] group-hover:text-[var(--brand-blue)] shrink-0 transition-colors" strokeWidth={1.8} />
                      <span className="text-[11.5px] font-medium text-[var(--text-secondary)] group-hover:text-[var(--brand-blue)] leading-tight transition-colors">{label}</span>
                    </button>
                  ))}
                </div>
              </div>

            </div>
          </div>

              ),
            },
            {
              id: 'driver-chart',
              className: '',
              children: (
          <div className="bg-[var(--surface)] rounded-[16px] p-5 text-left flex flex-col h-[280px]" style={{ boxShadow: 'var(--shadow-card)' }}>
            <span className="text-[13px] font-semibold text-[var(--text-primary)] mb-4 block">
              {t.dashboardPage.driverPerformanceTitle || 'Rendement par Chauffeur'}
            </span>
            <div className="flex-1 min-h-0">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={stats?.byDriver ?? []} barGap={5} barCategoryGap="42%">
                  <CartesianGrid strokeDasharray="2 3" vertical={false} stroke="var(--border)" />
                  <XAxis
                    dataKey="driverName"
                    tick={{ fontSize: 9, fill: 'var(--text-soft)', fontWeight: 600 }}
                    axisLine={false}
                    tickLine={false}
                    dy={6}
                    tickFormatter={capitalize}
                  />
                  <YAxis
                    tick={{ fontSize: 9, fill: 'var(--text-soft)' }}
                    axisLine={false}
                    tickLine={false}
                    width={18}
                  />
                  <Tooltip
                    contentStyle={{
                      background: 'var(--surface)',
                      border: '1px solid var(--border)',
                      borderRadius: '4px',
                      padding: '6px 10px',
                      fontSize: 10,
                      color: 'var(--text-primary)',
                      boxShadow: '0 2px 8px rgba(0,0,0,0.05)',
                    }}
                    cursor={{ fill: 'var(--hover-bg)' }}
                  />
                  <Bar name="Total" dataKey="total" fill="var(--border-strong)" barSize={12} radius={[2, 2, 0, 0]} />
                  <Bar name="Livrées" dataKey="delivered" fill="var(--text-primary)" barSize={12} radius={[2, 2, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
              ),
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
    <div className="bg-white border border-[#e0e0e0] rounded-[8px] p-4 hover:border-[#0972d3]/30 transition-colors">
      <div className="flex items-start justify-between mb-3">
        <span className="text-[12px] font-medium text-[#545b64]">{title}</span>
        <Icon size={16} strokeWidth={1.5} className="text-[#545b64]" />
      </div>
      <div className="font-mono text-[28px] font-semibold leading-none tabular-nums text-[#16191f]">{value}</div>
      <div className="text-[11px] text-[#545b64] mt-1.5 font-normal">{subtitle}</div>
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
        const scheduledDate = d.scheduledAt.split('T')[0];
        const now = new Date();
        const todayStr = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().split('T')[0];
        
        if (scheduledDate < todayStr) {
          return (
            <div className="mb-2">
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded border border-[#fecaca] bg-[#fef2f2] text-[#b91c1c] inline-flex items-center gap-1">
                <IconClock size={11} stroke={2.5} /> En retard (Planifié)
              </span>
            </div>
          );
        } else if (scheduledDate === todayStr) {
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
        const scheduledDate = d.scheduledAt.split('T')[0];
        const now = new Date();
        const todayStr = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().split('T')[0];
        
        if (scheduledDate < todayStr) {
          return (
            <div className="mb-2">
              <span className="text-[10px] font-bold px-1.5 py-0.5 rounded border border-[#fecaca] bg-[#fef2f2] text-[#b91c1c] inline-flex items-center gap-1">
                <IconClock size={11} stroke={2.5} /> En retard (Planifié)
              </span>
            </div>
          );
        } else if (scheduledDate === todayStr) {
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
