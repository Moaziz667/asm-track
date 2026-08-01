import { useEffect, useMemo, useState } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { useT } from '@/lib/i18n/LocaleContext';
import { cn } from '@/lib/utils';

import {
  IconDots, IconInbox,
  IconArrowRight, IconFilter,
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import ActivityTicker from '@/components/ActivityTicker';
import { Skeleton } from '@/components/ui/skeleton';
import type { AnalyticsScope } from '@/types';

import { DISPATCH_STATUSES, TONE_VAR } from './constants';
import { useDashboardData, type Range } from './useDashboardData';
import { GlobalFilterDrawer } from '@/components/analytics/GlobalFilterDrawer';
import { DeliveryCard, LotCard, type CardItem } from './cards';
import {
  NeedsAttentionWidget, FailureCausesWidget,
  DriverAvailabilityWidget, ActiveRoutesWidget, QuickActionsWidget,
  CycleTimeWidget, StatusBreakdownWidget,
  RadialKpiCard, BulletKpiCard, StatKpiCard, ReturnsWidget, BacklogWidget,
} from './widgets';
import ZoneDemandCards from './ZoneDemandCards';
import s from './Dashboard.module.scss';

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
    trend, deliveredDelta, failedDelta, lateDelta, slaDelta, vsPrev,
    driverGroups,
    laneMap, needsAttention, activeRoutes, focusedRouteId, setFocusedRouteId,
    driverName, getStatusConfig, kpi, ops, returns, counts,
  } = useDashboardData(range, customFrom, customTo, scope);

  const deliveredPct = today?.total ? Math.round((today.delivered / today.total) * 100) : 0;
  // Late = SLA-missed among measurable completed deliveries in the period (event-based, scoped).
  // Distinct from the live funnel breaches shown in the ops-counters widget.
  const lateOrders = Number(kpi?.lateOrders) || 0;
  const measurableOrders = Number(kpi?.measurableOrders) || 0;
  const lateRatePct = measurableOrders ? (lateOrders / measurableOrders) * 100 : 0;

  // Sparkline series derived from the 7-day trend so each KPI tile shows real signal
  // (not an empty reserved band). SLA proxy = daily delivered/count ratio.
  // Real daily SLA compliance from the backend (onTime/measurable per day); fall back to the
  // delivered/total proxy only if the field is absent (older payloads).
  const slaSpark = useMemo(
    () => trend.map(d => (d.slaRate != null ? d.slaRate : (d.count > 0 ? (d.delivered / d.count) * 100 : 0))),
    [trend],
  );
  const deliveredSpark = useMemo(() => trend.map(d => d.delivered), [trend]);
  const failedSpark = useMemo(() => trend.map(d => d.failed), [trend]);
  const lateSpark = useMemo(() => trend.map(d => d.late ?? 0), [trend]);

  // Delta formatters: percentage (previous>0) vs absolute count (previous=0). DeltaPill passes the
  // absolute magnitude; the arrow encodes direction.
  const pctFmt = (n: number) => `${Math.round(n)}%`;
  const absFmt = (n: number) => `${Math.round(n)}`;

  // The window every figure on this page is scoped to, said out loud in the toolbar.
  const periodLabel = useMemo(() => {
    const d = t.dashboardPage;
    switch (range) {
      case 'today': return d.periodToday;
      case 'yesterday': return d.periodYesterday;
      case 'last7d': return d.period7d;
      case 'custom': return customFrom && customTo ? `${customFrom} → ${customTo}` : d.periodCustom;
      default: return d.period30d;
    }
  }, [range, customFrom, customTo, t]);

  useEffect(() => {
    const cachedMode = localStorage.getItem('asm_dashboard_view');
    if (cachedMode === 'office' || cachedMode === 'kanban') setViewMode(cachedMode);
    // One-time cleanup: the draggable-grid layout persistence is gone (static dense grid now).
    try {
      for (let i = localStorage.length - 1; i >= 0; i--) {
        const key = localStorage.key(i);
        if (key && key.startsWith('widget-grid:')) localStorage.removeItem(key);
      }
    } catch { /* private mode — ignore */ }
  }, []);

  const handleViewChange = (mode: 'office' | 'kanban') => {
    setViewMode(mode);
    localStorage.setItem('asm_dashboard_view', mode);
  };

  return (
    <div className="w-full flex flex-col bg-[var(--app-bg)] min-h-[calc(100vh-56px)] animate-fadeIn relative">
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

      {/* ── Page toolbar ──────────────────────────────────────────────────────
          This replaces a `position: fixed` pill that floated over the app shell's own
          header, overlapping the search and the notification bell. Two chromes competing
          for the same corner is what made it feel bolted on — it was.

          The period now reads on the page instead of hiding inside the filter drawer:
          every number below is scoped to it, so a dashboard that does not say which
          window it is describing is a dashboard you cannot trust at a glance. */}
      <div className={s.toolbar}>
        <div className={s.toolbarLead}>
          <h1 className={s.pageTitle}>{t.dashboardPage.title}</h1>
          <span className={s.periodChip} aria-label={t.dashboardPage.periodAria}>{periodLabel}</span>
        </div>
        <div className={s.toolbarActions}>
          <SegmentedControl<'office' | 'kanban'>
            value={viewMode}
            onChange={handleViewChange}
            options={[
              { value: 'office', label: t.dashboardPage.viewOffice },
              { value: 'kanban', label: t.dashboardPage.viewKanban },
            ]}
          />
          <RefreshButton refreshing={refreshing} onClick={() => refetch()} />
          <button
            type="button"
            onClick={() => setDrawerOpen(true)}
            className={s.filterBtn}
            aria-label={t.dashboardPage.filtersLabel}
          >
            <IconFilter size={14} />
            <span>{t.dashboardPage.filtersLabel}</span>
            {activeFilterCount > 0 && <span className={s.filterCount}>{activeFilterCount}</span>}
          </button>
        </div>
      </div>

      {/* ── MAIN VIEW CONTENT SWITCHER ── */}
      {isLoading ? (
        /* The skeleton mirrors the real grid — four KPI tiles, then bands beside the rail.
           A four-column block of equal boxes described a page that does not exist, so the
           layout jumped the moment data arrived: the placeholder was its own small lie. */
        <div className={cn('flex-1 min-h-0 overflow-hidden animate-fadeIn', s.page)}>
          <div className={s.kpiStrip}>
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={`k${i}`} className="h-[92px] rounded-xl" />)}
          </div>
          <div className={s.body}>
            <div className={s.main}>
              <div className={cn(s.band, s.bandQuad)}>
                {Array.from({ length: 3 }).map((_, i) => <Skeleton key={`q${i}`} className="h-full rounded-xl" />)}
              </div>
              <div className={cn(s.band, s.bandTrend)}>
                {Array.from({ length: 2 }).map((_, i) => <Skeleton key={`t${i}`} className="h-full rounded-xl" />)}
              </div>
            </div>
            <div className={s.rail}>
              <div className={s.railFeed}><Skeleton className="h-full rounded-xl" /></div>
              <div className={s.railAttn}><Skeleton className="h-full rounded-xl" /></div>
            </div>
          </div>
        </div>
      ) : viewMode === 'office' ? (
        <div className={cn('flex-1 min-h-0 overflow-y-auto animate-fadeIn', s.page)}>
          {/* KPI strip — full width. Sparklines fed from the 7-day trend for real signal. */}
          <div className={s.kpiStrip}>
            <RadialKpiCard label={t.dashboardPage.slaRateLabel} value={`${slaPercent}%`} pct={slaPercent}
              spark={slaSpark}
              tone={measurableOrders > 0 ? (slaPercent >= 90 ? 'success' : slaPercent >= 70 ? 'warning' : 'danger') : 'default'}
              delta={slaDelta == null ? undefined : Math.round(slaDelta)} deltaCaption={vsPrev} deltaGood="up" deltaFormat={n => `${Math.round(n)} pts`} />
            <BulletKpiCard label={t.dashboardPage?.kpiDelivered ?? 'Delivered'} value={today?.delivered ?? 0} sub={`/ ${today?.total ?? 0}`}
              ratioPct={deliveredPct} tone="info" spark={deliveredSpark}
              delta={deliveredDelta.value ?? undefined} deltaCaption={vsPrev} deltaGood="up" deltaFormat={deliveredDelta.isPct ? pctFmt : absFmt} />
            <StatKpiCard label={t.dashboardPage?.kpiFailed ?? 'Failed'} value={today?.failed ?? 0}
              tone="danger" spark={failedSpark}
              delta={failedDelta.value ?? undefined} deltaCaption={vsPrev} deltaGood="down" deltaFormat={failedDelta.isPct ? pctFmt : absFmt} />
            <BulletKpiCard label={t.dashboardPage?.kpiLate ?? 'Late'} value={lateOrders} sub={`/ ${measurableOrders}`}
              ratioPct={lateRatePct} tone="danger" spark={lateSpark}
              delta={lateDelta.value ?? undefined} deltaCaption={vsPrev} deltaGood="down" deltaFormat={lateDelta.isPct ? pctFmt : absFmt} />
          </div>

          {/* Body — fluid main grid + fixed activity rail. Inverted pyramid:
              live/urgent on top, analytics/secondary at the bottom. */}
          <div className={s.body}>
            <div className={s.main}>
              {/* Quick read — under the KPIs */}
              <div className={cn(s.band, s.bandQuad)}>
                <BacklogWidget counts={counts} navigate={navigate} />
                <ReturnsWidget returns={returns} navigate={navigate} />
                <CycleTimeWidget stats={stats} />
              </div>
              {/* Status breakdown + zone overview */}
              <div className={cn(s.band, s.bandTrend)}>
                <StatusBreakdownWidget ops={ops} />
                <ZoneDemandCards range={range} from={customFrom} to={customTo} />
              </div>
              {/* Failure causes + driver status */}
              <div className={cn(s.band, s.bandPair)}>
                <FailureCausesWidget stats={stats} />
                <DriverAvailabilityWidget driverGroups={driverGroups} />
              </div>
              {/* Active routes + quick actions */}
              <div className={cn(s.band, s.bandPair)}>
                <ActiveRoutesWidget activeRoutes={activeRoutes} focusedRouteId={focusedRouteId} setFocusedRouteId={setFocusedRouteId} driverName={driverName} />
                <QuickActionsWidget navigate={navigate} />
              </div>
            </div>

            {/* Right rail — live activity feed (compact) + needs attention below */}
            <div className={s.rail}>
              <div className={s.railFeed}><ActivityTicker /></div>
              <div className={s.railAttn}><NeedsAttentionWidget items={needsAttention} navigate={navigate} /></div>
            </div>
          </div>
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
