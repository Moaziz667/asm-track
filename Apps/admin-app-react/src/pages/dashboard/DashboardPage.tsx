import { useEffect, useState } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/i18n/LocaleContext';

import {
  IconChartBar, IconAlertTriangle, IconTable, IconLayoutKanban, IconDots, IconInbox,
  IconArrowRight, IconServer, IconServerOff,
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { DraggableWidgetGrid } from '@/components/layout/DraggableWidgetGrid';
import ActivityTicker from '@/components/ActivityTicker';
import { Skeleton } from '@/components/ui/skeleton';
import type { DeliveryStatus } from '@/types';

import { DISPATCH_STATUSES, TONE_VAR } from './constants';
import { useDashboardData, type Range } from './useDashboardData';
import { AnalyticsFilterBar } from '@/components/analytics/AnalyticsFilterBar';
import { DeliveryCard, LotCard, type CardItem } from './cards';
import {
  TrendChartWidget, NeedsAttentionWidget, TopItemsWidget, FailureCausesWidget,
  DriverAvailabilityWidget, ActiveRoutesWidget, QuickActionsWidget,
  CycleTimeWidget, ZoneDensityWidget, StatusBreakdownWidget, OpsCountersWidget,
  RadialKpiCard, BulletKpiCard, SparkKpiCard,
} from './widgets';

export default function DashboardPage() {
  const t = useT();
  const navigate = useRouter();
  const { locale } = useLocaleStore();
  // Live-first: default to today, not all-time (a dashboard answers "what's happening now").
  const [range, setRange] = useState<Range>('today');
  const [customFrom, setCustomFrom] = useState('');
  const [customTo, setCustomTo] = useState('');
  const [viewMode, setViewMode] = useState<'office' | 'kanban'>('office');

  const {
    refreshing, isLoading, refetch, stats, today, overdueCount, slaPercent,
    trend, completionSpark, deliveredSpark, deliveredDelta, slaDelta, vsPrev, deliveredSub,
    activeRoutesCount, drivers, driverGroups, healthSummary, healthProblemsSummary,
    laneMap, needsAttention, activeRoutes, focusedRouteId, setFocusedRouteId,
    driverName, getStatusConfig, kpi, ops,
  } = useDashboardData(range, customFrom, customTo);

  const deliveredPct = today?.total ? Math.round((today.delivered / today.total) * 100) : 0;
  const failedSpark = trend.map(d => Number(d.failed) || 0);

  useEffect(() => {
    const cachedMode = localStorage.getItem('asm_dashboard_view');
    if (cachedMode === 'office' || cachedMode === 'kanban') setViewMode(cachedMode);
  }, []);

  const handleViewChange = (mode: 'office' | 'kanban') => {
    setViewMode(mode);
    localStorage.setItem('asm_dashboard_view', mode);
  };

  return (
    <div className="w-full flex flex-col bg-[var(--app-bg)] min-h-[calc(100vh-56px)] select-none animate-fadeIn">
      {/* ── HEADER PANEL ── */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0 shadow-2xs">
        <div className="px-6 py-2.5 flex items-center justify-between gap-6 max-w-[1900px] mx-auto">
          <div className="flex items-center gap-2.5 min-w-0">
            <h1 className="text-sm font-bold text-[var(--text-primary)] leading-tight tracking-tight shrink-0">{t.dashboardPage?.title || 'Tableau de bord'}</h1>
            {overdueCount > 0 && (
              <button
                type="button"
                onClick={() => navigate('/dispatch-desk?tab=queue')}
                className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md text-2xs font-semibold bg-[var(--danger-bg)] text-[var(--danger)] border border-[var(--danger)]/15 hover:border-[var(--danger)]/30 transition-colors cursor-pointer shrink-0"
              >
                <IconAlertTriangle size={10} className="shrink-0" />
                <span>{(t.dashboardPage.overdueChipLabel || '{count} non planifiées en retard').replace('{count}', String(overdueCount)).replace('{plural}', overdueCount > 1 ? 's' : '')}</span>
              </button>
            )}
          </div>

          <div className="flex items-center gap-3 shrink-0">
            <div className="flex items-center gap-1.5 me-2">
              <button type="button" onClick={() => handleViewChange('office')} className={cn('px-3 py-1 text-xs font-bold transition-all rounded-full cursor-pointer flex items-center gap-1 h-7 active:scale-[0.95]', viewMode === 'office' ? 'bg-background text-foreground border border-border shadow-2xs font-semibold' : 'text-muted-foreground hover:text-foreground bg-transparent')}>
                <IconTable size={12} />{locale === 'ar' ? 'الجدول' : 'Tableau'}
              </button>
              <button type="button" onClick={() => handleViewChange('kanban')} className={cn('px-3 py-1 text-xs font-bold transition-all rounded-full cursor-pointer flex items-center gap-1 h-7 active:scale-[0.95]', viewMode === 'kanban' ? 'bg-background text-foreground border border-border shadow-2xs font-semibold' : 'text-muted-foreground hover:text-foreground bg-transparent')}>
                <IconLayoutKanban size={12} />Kanban
              </button>
            </div>
            <AnalyticsFilterBar<Range>
              range={range}
              onRangeChange={setRange}
              options={[
                { value: 'today', label: t.dashboardPage.periodToday },
                { value: 'yesterday', label: t.dashboardPage.periodYesterday },
                { value: 'last7d', label: t.dashboardPage.period7d },
                { value: 'last30d', label: t.dashboardPage.period30d },
                { value: 'custom', label: t.dashboardPage.periodCustom },
              ]}
              from={customFrom}
              to={customTo}
              onFromChange={setCustomFrom}
              onToChange={setCustomTo}
              fromLabel={t.auditLogsPage?.fromLabel ?? 'Du'}
              toLabel={t.auditLogsPage?.toLabel ?? 'Au'}
              right={<RefreshButton refreshing={refreshing} onClick={() => refetch()} />}
            />
          </div>
        </div>
      </div>

      {/* ── PULSE BAND — operational health at a glance (icon+label+tone, no gradient) ── */}
      {!isLoading && (() => {
        const tone = healthSummary.allGood ? 'success' : healthSummary.downCount > 0 ? 'danger' : 'warning';
        const toneText = tone === 'success' ? 'var(--success)' : tone === 'danger' ? 'var(--danger)' : 'var(--warning)';
        const toneBg = tone === 'success' ? 'var(--success-bg)' : tone === 'danger' ? 'var(--danger-bg)' : 'var(--warning-bg)';
        const statusLabel = healthSummary.allGood
          ? (t.dashboardPage.pulseNominal ?? 'Opérations nominales')
          : `${healthSummary.downCount > 0 ? (t.dashboardPage.systemHealthOffline ?? 'Hors ligne') : (t.dashboardPage.systemHealthDegraded ?? 'Dégradé')}${healthProblemsSummary ? ' · ' + healthProblemsSummary : ''}`;
        return (
          <div className="border-b border-[var(--border)]" style={{ background: toneBg }}>
            <div className="px-6 py-2.5 max-w-[1800px] mx-auto flex items-center gap-x-6 gap-y-1 flex-wrap">
              <span className="inline-flex items-center gap-2 text-xs font-semibold" style={{ color: toneText }}>
                {healthSummary.allGood ? <IconServer size={14} /> : <IconServerOff size={14} />}
                {statusLabel}
              </span>
              <span className="text-xs text-[var(--text-secondary)]">
                {t.dashboardPage.slaRateLabel} <b className="font-semibold tabular-nums">{slaPercent}%</b>
              </span>
              <button type="button" onClick={() => navigate('/dispatch-desk?tab=queue')}
                className="text-xs text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors">
                {t.dashboardPage.pulseToProcess ?? 'À traiter'} <b className="font-semibold tabular-nums">{overdueCount}</b>
              </button>
              <span className="text-xs text-[var(--text-secondary)]">
                {t.dashboardPage.pulseServices ?? 'Services'} <b className="font-semibold tabular-nums">{healthSummary.serviceCount > 0 ? `${healthSummary.okServices}/${healthSummary.serviceCount}` : '--'}</b>
              </span>
            </div>
          </div>
        );
      })()}

      {/* ── MAIN VIEW CONTENT SWITCHER ── */}
      {isLoading ? (
        <div className="px-6 py-6 w-full max-w-[1800px] mx-auto flex-1 animate-fadeIn overflow-hidden">
          <div className="grid grid-cols-4 gap-4">
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={`k${i}`} className="h-[110px] rounded-xl" />)}
            {Array.from({ length: 4 }).map((_, i) => <Skeleton key={`w${i}`} className="h-[260px] rounded-xl" />)}
          </div>
        </div>
      ) : viewMode === 'office' ? (
        <div className="flex-1 min-h-0 overflow-hidden flex gap-3 px-4 py-3 w-full max-w-[1900px] mx-auto animate-fadeIn">
          <div className="flex-1 min-w-0 overflow-y-auto pe-1">
          <DraggableWidgetGrid
            storageKey="dashboard-v8-stats"
            margin={[12, 12]}
            items={[
              {
                id: 'kpi-sla', defaultLayout: { w: 3, h: 2, x: 0, y: 0, minW: 2, minH: 2 }, className: '',
                children: <RadialKpiCard label={t.dashboardPage.slaRateLabel} value={`${slaPercent}%`} pct={slaPercent}
                  tone={(today?.total ?? 0) > 0 ? (slaPercent >= 90 ? 'success' : slaPercent >= 70 ? 'warning' : 'danger') : 'default'}
                  delta={slaDelta == null ? undefined : Math.round(slaDelta)} deltaCaption={vsPrev} deltaGood="up" deltaFormat={n => `${Math.round(n)} pts`} />,
              },
              {
                id: 'kpi-delivered', defaultLayout: { w: 3, h: 2, x: 3, y: 0, minW: 2, minH: 2 }, className: '',
                children: <BulletKpiCard label={t.dashboardPage.kpiDelivered || 'Livrées'} value={today?.delivered ?? 0} sub={`/ ${today?.total ?? 0}`}
                  ratioPct={deliveredPct} tone="info"
                  delta={deliveredDelta == null ? undefined : deliveredDelta} deltaCaption={vsPrev} deltaGood="up" deltaFormat={n => `${n.toFixed(0)}%`} />,
              },
              {
                id: 'kpi-failed', defaultLayout: { w: 3, h: 2, x: 6, y: 0, minW: 2, minH: 2 }, className: '',
                children: <SparkKpiCard label={t.dashboardPage.kpiFailed || 'Échecs'} value={today?.failed ?? 0} spark={failedSpark} tone="danger" deltaGood="down" />,
              },
              {
                id: 'kpi-late', defaultLayout: { w: 3, h: 2, x: 9, y: 0, minW: 2, minH: 2 }, className: '',
                children: <BulletKpiCard label={t.dashboardPage.kpiLate || 'Retards'} value={overdueCount} sub={`/ ${today?.total ?? 0}`}
                  ratioPct={today?.total ? (overdueCount / today.total) * 100 : 0} tone="danger" />,
              },
              { id: 'status-breakdown', defaultLayout: { w: 4, h: 5, x: 0, y: 3, minW: 3, minH: 4 }, className: '', children: <StatusBreakdownWidget stats={stats} /> },
              { id: 'ops-counters', defaultLayout: { w: 4, h: 5, x: 4, y: 3, minW: 3, minH: 3 }, className: '', children: <OpsCountersWidget kpi={kpi} ops={ops} /> },
              { id: 'cycle-time', defaultLayout: { w: 4, h: 5, x: 8, y: 3, minW: 3, minH: 4 }, className: '', children: <CycleTimeWidget stats={stats} /> },
              { id: 'trend-chart', defaultLayout: { w: 8, h: 6, x: 0, y: 8, minW: 6, minH: 4 }, className: '', children: <TrendChartWidget trend={trend} /> },
              { id: 'zone-density', defaultLayout: { w: 4, h: 6, x: 8, y: 8, minW: 3, minH: 4 }, className: '', children: <ZoneDensityWidget kpi={kpi} /> },
              { id: 'top-items', defaultLayout: { w: 6, h: 5, x: 0, y: 14, minW: 3, minH: 4 }, className: '', children: <TopItemsWidget stats={stats} /> },
              { id: 'failure-causes', defaultLayout: { w: 6, h: 5, x: 6, y: 14, minW: 3, minH: 4 }, className: '', children: <FailureCausesWidget stats={stats} /> },
              { id: 'quick-actions', defaultLayout: { w: 12, h: 2, x: 0, y: 19, minW: 3, minH: 2 }, className: '', children: <QuickActionsWidget navigate={navigate} /> },
            ]}
          />
          </div>
          {/* Decorative separator line between main grid and sidebar */}
          <div className="w-px bg-gradient-to-b from-transparent via-[var(--border)] to-transparent shrink-0 my-4" />
          {/* RIGHT — live rail (operational: à-traiter + activité + fleet; ignores the date filter) */}
          <aside className="w-[300px] shrink-0 flex flex-col">
            <div className="flex flex-col flex-1 min-h-0 overflow-y-auto [&_.card]:!border-0 [&_.card]:!rounded-none [&_.card]:!shadow-none [&_.card]:!bg-transparent" style={{ scrollbarWidth: 'thin' }}>
              {needsAttention.length > 0 && (
                <div className="shrink-0"><NeedsAttentionWidget items={needsAttention} navigate={navigate} /></div>
              )}
              {/* Thin decorative line between Nécessite attention and Live */}
              <div className="h-px bg-gradient-to-r from-transparent via-[var(--text-muted)]/20 to-transparent shrink-0 mx-3" />
              <div className="shrink-0"><ActivityTicker /></div>
              <div className="h-px bg-gradient-to-r from-transparent via-[var(--text-muted)]/20 to-transparent shrink-0 mx-3" />
              <div className="shrink-0"><DriverAvailabilityWidget driverGroups={driverGroups} /></div>
              <div className="shrink-0"><ActiveRoutesWidget activeRoutes={activeRoutes} focusedRouteId={focusedRouteId} setFocusedRouteId={setFocusedRouteId} driverName={driverName} /></div>
            </div>
          </aside>
        </div>
      ) : (
        /* ── KANBAN VIEW ── */
        <div className="px-6 pb-6 w-full max-w-[1800px] mx-auto flex flex-col flex-1 min-h-0 overflow-hidden animate-fadeIn">
          <div className="pt-4 pb-2 flex items-center justify-between">
            <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-muted)]">{t.dashboardPage.dispatchFlowTitle || 'Flux de Dispatch'}</span>
            <button type="button" onClick={() => window.open('/route-builder', '_blank')} className="group flex items-center gap-1 text-xs font-bold text-[var(--text-muted)] hover:text-[var(--text-primary)] transition-colors cursor-pointer">
              {t.dashboardPage.plannerButton || 'Planificateur'} <IconArrowRight size={12} className="group-hover:translate-x-0.5 transition-transform" />
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
                          <span className="text-2xs font-bold text-[var(--text-soft)]">{t.dashboardPage.emptyState || 'Vide'}</span>
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
    <div className="card p-4">
      <div className="flex items-start justify-between mb-3">
        <span className="text-sm font-medium text-[var(--text-secondary)]">{title}</span>
        <Icon size={16} strokeWidth={1.5} className="text-[var(--text-secondary)]" />
      </div>
      <div className="font-mono text-3xl font-semibold leading-none tabular-nums text-[var(--text-primary)]">{value}</div>
      <div className="text-xs text-[var(--text-soft)] mt-1.5 font-normal">{subtitle}{trend}</div>
    </div>
  );
}
