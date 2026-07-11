import { useEffect, useMemo, useState } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { useT } from '@/lib/i18n/LocaleContext';

import {
  IconTable, IconLayoutKanban, IconDots, IconInbox,
  IconArrowRight, IconFilter,
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { DraggableWidgetGrid } from '@/components/layout/DraggableWidgetGrid';
import ActivityTicker from '@/components/ActivityTicker';
import { Skeleton } from '@/components/ui/skeleton';
import type { AnalyticsScope } from '@/types';

import { DISPATCH_STATUSES, TONE_VAR } from './constants';
import { useDashboardData, type Range } from './useDashboardData';
import { GlobalFilterDrawer } from '@/components/analytics/GlobalFilterDrawer';
import { DeliveryCard, LotCard, type CardItem } from './cards';
import {
  TrendChartWidget, NeedsAttentionWidget, TopItemsWidget, FailureCausesWidget,
  DriverAvailabilityWidget, ActiveRoutesWidget, QuickActionsWidget,
  CycleTimeWidget, StatusBreakdownWidget, OpsCountersWidget,
  RadialKpiCard, BulletKpiCard, StatKpiCard,
} from './widgets';
import ZoneDemandCards from './ZoneDemandCards';

export default function DashboardPage() {
  const t = useT();
  const navigate = useRouter();
  // Live-first: default to today, not all-time (a dashboard answers "what's happening now").
  const [range, setRange] = useState<Range>('last30d');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [viewMode, setViewMode] = useState<'office' | 'kanban'>('office');
  const [scope, setScope] = useState<AnalyticsScope>({});
  const [drawerOpen, setDrawerOpen] = useState(false);
  const activeFilterCount = useMemo(() => {
    const scopeCount = Object.values(scope).reduce((n, v) => n + (Array.isArray(v) ? v.length : 0), 0);
    return scopeCount + (range !== 'last30d' ? 1 : 0);
  }, [scope, range]);

  const {
    refreshing, isLoading, refetch, stats, today, slaPercent,
    trend, deliveredDelta, failedDelta, lateDelta, slaDelta, vsPrev, deliveredSub,
    activeRoutesCount, drivers, driverGroups,
    laneMap, needsAttention, activeRoutes, focusedRouteId, setFocusedRouteId,
    driverName, getStatusConfig, kpi, ops,
  } = useDashboardData(range, customFrom, customTo, scope);

  const deliveredPct = today?.total ? Math.round((today.delivered / today.total) * 100) : 0;
  // Late = SLA-missed among measurable completed deliveries in the period (event-based, scoped).
  // Distinct from the live funnel breaches shown in the ops-counters widget.
  const lateOrders = Number(kpi?.lateOrders) || 0;
  const measurableOrders = Number(kpi?.measurableOrders) || 0;
  const lateRatePct = measurableOrders ? (lateOrders / measurableOrders) * 100 : 0;

  useEffect(() => {
    const cachedMode = localStorage.getItem('asm_dashboard_view');
    if (cachedMode === 'office' || cachedMode === 'kanban') setViewMode(cachedMode);
  }, []);

  const handleViewChange = (mode: 'office' | 'kanban') => {
    setViewMode(mode);
    localStorage.setItem('asm_dashboard_view', mode);
  };

  return (
    <div className="w-full flex flex-col bg-[var(--app-bg)] min-h-[calc(100vh-56px)] select-none animate-fadeIn relative">
      {/* ── FLOATING ACTION BAR ── */}
      <div className="fixed top-16 right-4 z-40 flex items-center gap-1.5 bg-[var(--surface)] border border-[var(--border)] rounded-lg shadow-lg px-1.5 py-1">
        <button
          type="button"
          onClick={() => handleViewChange(viewMode === 'office' ? 'kanban' : 'office')}
          className="w-7 h-7 flex items-center justify-center rounded-md text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer"
          title={viewMode === 'office' ? 'Kanban' : 'Table'}
        >
          {viewMode === 'office' ? <IconLayoutKanban size={14} /> : <IconTable size={14} />}
        </button>
        <RefreshButton refreshing={refreshing} onClick={() => refetch()} />
        <button
          type="button"
          onClick={() => setDrawerOpen(true)}
          className="relative w-7 h-7 flex items-center justify-center rounded-md text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer"
        >
          <IconFilter size={14} />
          {activeFilterCount > 0 && (
            <span className="absolute -top-0.5 -right-0.5 w-3.5 h-3.5 rounded-full bg-[var(--brand)] text-white text-2xs font-bold flex items-center justify-center">
              {activeFilterCount}
            </span>
          )}
        </button>
      </div>
      <GlobalFilterDrawer
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
        value={scope}
        onChange={setScope}
        resultCount={today?.total}
        range={range}
        onRangeChange={setRange}
        defaultRange="last30d"
        rangeOptions={[
          { value: 'today', label: t.dashboardPage.periodToday },
          { value: 'yesterday', label: t.dashboardPage.periodYesterday },
          { value: 'last7d', label: t.dashboardPage.period7d },
          { value: 'last30d', label: t.dashboardPage.period30d },
          { value: 'custom', label: t.dashboardPage.periodCustom },
        ]}
        customFrom={customFrom}
        customTo={customTo}
        onCustomFromChange={setCustomFrom}
        onCustomToChange={setCustomTo}
      />

      {/* ── MAIN VIEW CONTENT SWITCHER ── */}
      {isLoading ? (
        <div className="px-6 py-6 w-full max-w-[1800px] mx-auto flex-1 animate-fadeIn overflow-hidden">
          <div className="grid grid-cols-4 gap-4">
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={`k${i}`} className="h-[110px] rounded-xl" />)}
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={`w${i}`} className="h-[260px] rounded-xl" />)}
          </div>
        </div>
      ) : viewMode === 'office' ? (
        <div className="flex-1 min-h-0 overflow-y-auto px-4 py-3 w-full max-w-[1900px] mx-auto animate-fadeIn">
          <DraggableWidgetGrid
            storageKey="dashboard-v11-stats"
            margin={[12, 12]}
            items={[
              {
                id: 'kpi-sla', defaultLayout: { w: 2, h: 3, x: 0, y: 0, minW: 2, minH: 2 }, className: '',
                children: <RadialKpiCard label={t.dashboardPage.slaRateLabel} value={`${slaPercent}%`} pct={slaPercent}
                  tone={measurableOrders > 0 ? (slaPercent >= 90 ? 'success' : slaPercent >= 70 ? 'warning' : 'danger') : 'default'}
                  delta={slaDelta == null ? undefined : Math.round(slaDelta)} deltaCaption={vsPrev} deltaGood="up" deltaFormat={n => `${Math.round(n)} pts`} />,
              },
              {
                id: 'kpi-delivered', defaultLayout: { w: 2, h: 3, x: 2, y: 0, minW: 2, minH: 2 }, className: '',
                children: <BulletKpiCard label={t.dashboardPage?.kpiDelivered ?? 'Delivered'} value={today?.delivered ?? 0} sub={`/ ${today?.total ?? 0}`}
                  ratioPct={deliveredPct}
                  tone="info"
                  delta={deliveredDelta == null ? undefined : deliveredDelta} deltaCaption={vsPrev} deltaGood="up" deltaFormat={n => `${n.toFixed(0)}%`} />,
              },
              {
                id: 'kpi-failed', defaultLayout: { w: 2, h: 3, x: 4, y: 0, minW: 2, minH: 2 }, className: '',
                children: <StatKpiCard label={t.dashboardPage?.kpiFailed ?? 'Failed'} value={today?.failed ?? 0}
                  tone="danger" delta={failedDelta ?? undefined} deltaCaption={vsPrev} deltaGood="down" deltaFormat={n => `${n.toFixed(0)}%`} />,
              },
              {
                id: 'kpi-late', defaultLayout: { w: 3, h: 3, x: 6, y: 0, minW: 2, minH: 2 }, className: '',
                children: <BulletKpiCard label={t.dashboardPage?.kpiLate ?? 'Late'} value={lateOrders} sub={`/ ${measurableOrders}`}
                  ratioPct={lateRatePct}
                  tone="danger" delta={lateDelta ?? undefined} deltaCaption={vsPrev} deltaGood="down" deltaFormat={n => `${n.toFixed(0)}%`} />,
              },
              { id: 'status-breakdown', defaultLayout: { w: 3, h: 5, x: 0, y: 3, minW: 2, minH: 4 }, className: '', children: <StatusBreakdownWidget ops={ops} /> },
              { id: 'ops-counters', defaultLayout: { w: 3, h: 5, x: 3, y: 3, minW: 2, minH: 3 }, className: '', children: <OpsCountersWidget ops={ops} /> },
              { id: 'cycle-time', defaultLayout: { w: 3, h: 6, x: 6, y: 3, minW: 2, minH: 4 }, className: '', children: <CycleTimeWidget stats={stats} /> },
              { id: 'trend-chart', defaultLayout: { w: 6, h: 6, x: 0, y: 8, minW: 4, minH: 4 }, className: '', children: <TrendChartWidget trend={trend} /> },
              { id: 'zone-density', defaultLayout: { w: 3, h: 5, x: 6, y: 9, minW: 2, minH: 4 }, className: '', children: <ZoneDemandCards range={range} from={customFrom} to={customTo} /> },
              { id: 'top-items', defaultLayout: { w: 4, h: 6, x: 0, y: 14, minW: 3, minH: 4 }, className: '', children: <TopItemsWidget stats={stats} /> },
              { id: 'failure-causes', defaultLayout: { w: 5, h: 6, x: 4, y: 14, minW: 3, minH: 4 }, className: '', children: <FailureCausesWidget stats={stats} /> },
              { id: 'quick-actions', defaultLayout: { w: 4, h: 5, x: 0, y: 20, minW: 3, minH: 2 }, className: '', children: <QuickActionsWidget navigate={navigate} /> },
              /* ── Right-locked widgets (colonne 9-11, 只能纵向互换) ── */
              {
                id: 'needs-attention', defaultLayout: { w: 3, h: 8, x: 9, y: 0, minW: 3, maxW: 3, minH: 4 }, className: '',
                children: <NeedsAttentionWidget items={needsAttention} navigate={navigate} />,
              },
              {
                id: 'live-activity', defaultLayout: { w: 3, h: 7, x: 9, y: 8, minW: 3, maxW: 3, minH: 4 }, className: '',
                children: <ActivityTicker />,
              },
              {
                id: 'driver-availability', defaultLayout: { w: 3, h: 5, x: 9, y: 15, minW: 3, maxW: 3, minH: 3 }, className: '',
                children: <DriverAvailabilityWidget driverGroups={driverGroups} />,
              },
              {
                id: 'active-routes', defaultLayout: { w: 3, h: 5, x: 9, y: 20, minW: 3, maxW: 3, minH: 3 }, className: '',
                children: <ActiveRoutesWidget activeRoutes={activeRoutes} focusedRouteId={focusedRouteId} setFocusedRouteId={setFocusedRouteId} driverName={driverName} />,
              },
            ]}
          />
        </div>
      ) : (
        /* ── KANBAN VIEW ── */
        <div className="px-6 pb-6 w-full max-w-[1800px] mx-auto flex flex-col flex-1 min-h-0 overflow-hidden animate-fadeIn">
          <div className="pt-4 pb-2 flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-muted)]">{t.dashboardPage?.dispatchFlowTitle ?? 'Dispatch Flow'}</span>
            <button type="button" onClick={() => window.open('/route-builder', '_blank')} className="group flex items-center gap-1 text-xs font-bold text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors cursor-pointer">
              {t.dashboardPage?.plannerButton ?? 'Planner'} <IconArrowRight size={12} className="group-hover:translate-x-0.5 transition-transform" />
            </button>
          </div>

          <div className="overflow-x-auto border border-[var(--border)] rounded-lg bg-[var(--surface)] shadow-2xs">
            <div className="flex flex-nowrap items-stretch" style={{ height: 'calc(100vh - 180px)', minHeight: 580 }}>
              {DISPATCH_STATUSES.map(status => {
                const config = getStatusConfig(status);
                const data = laneMap[status];
                const items = data?.items ?? [];
                const toneVar = TONE_VAR[config.tone as import('./constants').StatusTone] ?? 'var(--text-muted)';
                return (
                  <div key={status} className="border-r border-[var(--border)] last:border-r-0 flex flex-col bg-[var(--surface)]" style={{ width: 268, flexShrink: 0, height: '100%' }}>
                    <div className="flex-none px-3 py-2.5 flex items-center justify-between border-b border-[var(--border)] bg-[var(--surface)]">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-xs font-semibold uppercase tracking-wider truncate" style={{ color: toneVar }}>{config.label}</span>
                        <span className="text-2xs font-mono font-bold tabular-nums px-1.5 py-0.5 rounded-full bg-[var(--hover-bg)] text-[var(--text-muted)]">{data.count}</span>
                      </div>
                      <button type="button" className="w-5 h-5 flex items-center justify-center rounded text-[var(--text-soft)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer">
                        <IconDots size={13} />
                      </button>
                    </div>
                    <div className="flex-1 overflow-y-auto p-2 flex flex-col gap-2" style={{ scrollbarWidth: 'thin' }}>
                      {items.length === 0 ? (
                        <div className="flex flex-col items-center justify-center flex-1 py-12 gap-2 opacity-45">
                          <IconInbox size={18} stroke={1.2} className="text-[var(--text-soft)]" />
                          <span className="text-2xs font-bold text-[var(--text-soft)]">{t.dashboardPage?.emptyState ?? 'Empty'}</span>
                        </div>
                      ) : (
                        items.map((d: CardItem, idx: number) => {
                          const isLot = d.isLot || (d.deliveriesCount ?? 0) > 1 || d.orderRef?.startsWith('LOT');
                          return isLot
                            ? <LotCard key={idx} d={d} status={status} />
                            : <DeliveryCard key={idx} d={d} status={status} />;
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

/** Legacy KPI card kept for PerformancePage (imports { KpiCard } from './DashboardPage'). */
export function KpiCard({ title, value, subtitle, Icon, color: _color, trend }: { title: string; value: string | number; subtitle: string; Icon: React.ElementType; color?: string; trend?: string }) {
  return (
    <div className="border border-[var(--border)] rounded-lg p-4">
      <div className="flex items-start justify-between mb-3">
        <span className="text-sm font-medium text-[var(--text-secondary)]">{title}</span>
        <Icon size={16} strokeWidth={1.5} className="text-[var(--text-secondary)]" />
      </div>
      <div className="font-mono text-3xl font-semibold leading-none tabular-nums text-[var(--text-primary)]">{value}</div>
      <div className="text-xs text-[var(--text-soft)] mt-1.5 font-normal">{subtitle}{trend}</div>
    </div>
  );
}
