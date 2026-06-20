import { useEffect, useState } from 'react';
import { useNavigate as useRouter } from 'react-router-dom';
import { cn } from '@/lib/utils';
import { useLocaleStore } from '@/lib/i18n';
import { useT } from '@/lib/LocaleContext';
import { useIsDark } from '@/lib/theme';
import {
  IconChartBar, IconAlertTriangle, IconTable, IconLayoutKanban, IconDots, IconInbox,
  IconArrowRight, IconServer, IconServerOff,
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { DraggableWidgetGrid } from '@/components/layout/DraggableWidgetGrid';
import ActivityTicker from '@/components/ActivityTicker';
import { KPICard } from '@/components/ui/kpi-card';
import type { DeliveryStatus } from '@/types';

import { DISPATCH_STATUSES, KANBAN_GRADIENT_MAP } from './dashboard/constants';
import { useDashboardData } from './dashboard/useDashboardData';
import { DeliveryCard, LotCard } from './dashboard/cards';
import {
  TrendChartWidget, NeedsAttentionWidget, TopItemsWidget, FailureCausesWidget,
  DriverAvailabilityWidget, ActiveRoutesWidget, QuickActionsWidget,
} from './dashboard/widgets';

export default function DashboardPage() {
  const t = useT();
  const navigate = useRouter();
  const isDark = useIsDark();
  const { locale } = useLocaleStore();
  const [period, setPeriod] = useState<'day' | 'week' | 'month' | 'all'>('all');
  const [viewMode, setViewMode] = useState<'office' | 'kanban'>('office');

  const {
    refreshing, refetch, stats, today, overdueCount, slaPercent,
    trend, completionSpark, deliveredSpark, deliveredDelta, slaDelta, vsPrev, deliveredSub,
    activeRoutesCount, drivers, driverGroups, healthSummary, healthProblemsSummary,
    laneMap, needsAttention, activeRoutes, focusedRouteId, setFocusedRouteId,
    driverName, getStatusConfig,
  } = useDashboardData(period);

  const deliveredPct = today?.total ? Math.round((today.delivered / today.total) * 100) : 0;

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
        <div className="px-6 py-4 flex items-center justify-between gap-6 max-w-[1800px] mx-auto">
          <div className="flex flex-col text-start">
            <h1 className="text-base font-bold text-[var(--text-primary)] leading-tight tracking-tight">{t.dashboardPage?.title || 'Tableau de bord'}</h1>
            <div className="flex items-center gap-2 mt-1 flex-wrap">
              <span className="text-xs text-[var(--text-muted)] font-medium">{t.dashboardPage?.subtitle || 'Supervision administrative et indicateurs opérationnels'}</span>
              {overdueCount > 0 && (
                <button
                  onClick={() => navigate('/dispatch-desk?tab=queue')}
                  style={{
                    backgroundColor: isDark ? 'rgba(239, 68, 68, 0.15)' : '#DC2626',
                    color: isDark ? '#F87171' : '#FFFFFF',
                    borderColor: isDark ? 'rgba(239, 68, 68, 0.3)' : '#B91C1C',
                  }}
                  className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-2xs font-bold border transition-all cursor-pointer shadow-2xs"
                >
                  <IconAlertTriangle size={11} className="animate-pulse" style={{ color: isDark ? '#F87171' : '#FFFFFF' }} />
                  <span>{(t.dashboardPage.overdueChipLabel || '{count} non planifiées en retard').replace('{count}', String(overdueCount)).replace('{plural}', overdueCount > 1 ? 's' : '')}</span>
                </button>
              )}
            </div>
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
            <div className="flex items-center gap-1 me-1">
              {(['day', 'week', 'month', 'all'] as const).map((p) => {
                const labelMap = { day: t.dashboardPage.periodDay, week: t.dashboardPage.periodWeek, month: t.dashboardPage.periodMonth, all: t.dashboardPage.periodAll };
                const active = period === p;
                return (
                  <button key={p} type="button" onClick={() => setPeriod(p)} className={cn('px-3 py-1 text-xs font-bold transition-all rounded-full cursor-pointer h-7 flex items-center justify-center active:scale-[0.95]', active ? 'bg-background text-foreground border border-border shadow-2xs font-semibold' : 'text-muted-foreground hover:text-foreground bg-transparent')}>
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
        <div className="px-6 py-6 w-full max-w-[1800px] mx-auto flex-1 animate-fadeIn overflow-y-auto">
          <DraggableWidgetGrid
            storageKey="dashboard-v7"
            items={[
              {
                id: 'kpi-sla', defaultLayout: { w: 2, h: 3, x: 0, y: 0, minW: 2, minH: 2 }, className: '',
                children: <KPICard label={t.dashboardPage.slaRateLabel} value={`${slaPercent}%`}
                  trend={slaDelta == null ? undefined : { delta: Math.round(slaDelta), format: n => `${Math.abs(n)} pts`, goodWhen: 'up', caption: vsPrev }}
                  sparklineData={completionSpark.length > 0 ? completionSpark : undefined}
                  tone={(today?.total ?? 0) > 0 ? (slaPercent >= 90 ? 'success' : slaPercent >= 70 ? 'warning' : 'danger') : 'default'} className="h-full" />,
              },
              {
                id: 'kpi-delivered', defaultLayout: { w: 2, h: 3, x: 2, y: 0, minW: 2, minH: 2 }, className: '',
                children: <KPICard label={t.dashboardPage.kpiDelivered || 'Livrés'} value={today?.delivered ?? 0}
                  trend={deliveredDelta == null ? undefined : { delta: deliveredDelta, format: n => `${Math.abs(n).toFixed(0)}%`, goodWhen: 'up', caption: vsPrev }}
                  sub={
                    <div className="flex flex-col gap-1 w-full mt-1.5">
                      <div className="flex items-center justify-between text-2xs text-[var(--text-soft)]">
                        <span>{today?.delivered ?? 0} / {today?.total ?? 0} {t.dashboardPage.kpiDelivered || 'Livrés'}</span>
                        <span>{deliveredPct}%</span>
                      </div>
                      <div className="w-full bg-[var(--border)] h-1.5 rounded-full overflow-hidden">
                        <div className="bg-[var(--info)] h-full rounded-full transition-all duration-500" style={{ width: `${deliveredPct}%` }} />
                      </div>
                    </div>
                  }
                  sparklineData={deliveredSpark.length > 0 ? deliveredSpark : undefined}
                  tone={(today?.delivered ?? 0) > 0 ? 'info' : 'default'} className="h-full" />,
              },
              {
                id: 'kpi-drivers', defaultLayout: { w: 2, h: 3, x: 4, y: 0, minW: 2, minH: 2 }, className: '',
                children: <KPICard label={t.dashboardPage.kpiDriversOnline || 'En ligne'} value={driverGroups.online.length}
                  sub={`/ ${drivers.length} ${t.dashboardPage.kpiDriversTotalSuffix || 'total'}`} tone={driverGroups.online.length === 0 ? 'danger' : 'default'} className="h-full" />,
              },
              {
                id: 'kpi-system-health', defaultLayout: { w: 2, h: 3, x: 6, y: 0, minW: 2, minH: 2 }, className: '',
                children: <KPICard
                  label={t.dashboardPage.systemHealthLabel || 'Santé Système'}
                  value={healthSummary.serviceCount > 0 ? `${healthSummary.okServices} / ${healthSummary.serviceCount}` : '--'}
                  sub={healthSummary.allGood ? t.dashboardPage.systemHealthOptimal || 'Optimal' : `${healthSummary.downCount > 0 ? (t.dashboardPage.systemHealthOffline || 'Hors ligne') : (t.dashboardPage.systemHealthDegraded || 'Dégradé')} · ${healthProblemsSummary}`}
                  icon={healthSummary.allGood ? <IconServer size={16} className="text-[var(--success)]" /> : <IconServerOff size={16} className={healthSummary.downCount > 0 ? 'text-[var(--danger)]' : 'text-[var(--warning)]'} />}
                  tone={healthSummary.allGood ? 'success' : healthSummary.downCount > 0 ? 'danger' : 'warning'}
                  onClick={() => navigate('/system-health')} className="h-full" />,
              },
              { id: 'trend-chart', defaultLayout: { w: 8, h: 6, x: 0, y: 3, minW: 6, minH: 4 }, className: '', children: <TrendChartWidget trend={trend} /> },
              ...(needsAttention.length > 0 ? [{ id: 'needs-attention', defaultLayout: { w: 4, h: 9, x: 8, y: 0, minW: 3, minH: 4 }, className: '', children: <NeedsAttentionWidget items={needsAttention} navigate={navigate} /> }] : []),
              { id: 'top-items', defaultLayout: { w: 4, h: 6, x: 0, y: 9, minW: 3, minH: 4 }, className: '', children: <TopItemsWidget stats={stats} /> },
              { id: 'failure-causes', defaultLayout: { w: 4, h: 6, x: 4, y: 9, minW: 3, minH: 4 }, className: '', children: <FailureCausesWidget stats={stats} /> },
              { id: 'activity-ticker', defaultLayout: { w: 4, h: 6, x: 8, y: 9, minW: 3, minH: 4 }, className: '', children: <ActivityTicker /> },
              { id: 'driver-availability', defaultLayout: { w: 4, h: 6, x: 0, y: 15, minW: 3, minH: 4 }, className: '', children: <DriverAvailabilityWidget driverGroups={driverGroups} /> },
              { id: 'section-start', defaultLayout: { w: 4, h: 6, x: 4, y: 15, minW: 3, minH: 4 }, className: '', children: <ActiveRoutesWidget activeRoutes={activeRoutes} focusedRouteId={focusedRouteId} setFocusedRouteId={setFocusedRouteId} driverName={driverName} /> },
              { id: 'quick-actions', defaultLayout: { w: 4, h: 6, x: 8, y: 15, minW: 3, minH: 4 }, className: '', children: <QuickActionsWidget navigate={navigate} /> },
            ]}
          />
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
                const gradient = KANBAN_GRADIENT_MAP[status] || 'var(--gradient-blue)';
                return (
                  <div key={status} className="border-r border-[var(--border)] last:border-r-0 flex flex-col bg-[var(--app-bg)]/35" style={{ width: 268, flexShrink: 0, height: '100%' }}>
                    <div style={{ height: 3, background: gradient, width: '100%' }} />
                    <div className="flex-none px-3 py-2.5 flex items-center justify-between border-b border-[var(--border)] bg-[var(--surface)]">
                      <div className="flex items-center gap-1.5 min-w-0">
                        <span className="px-2 py-0.5 rounded-full text-2xs font-bold leading-none tracking-tight" style={{ backgroundColor: `${config.color}15`, color: config.color }}>{config.label}</span>
                        <span className="text-2xs font-mono font-bold text-[var(--text-soft)]">{data.count}</span>
                      </div>
                      <button type="button" className="w-5 h-5 flex items-center justify-center rounded text-[var(--text-soft)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer">
                        <IconDots size={13} />
                      </button>
                    </div>
                    <div className="flex-1 overflow-y-auto p-2.5 flex flex-col gap-2" style={{ scrollbarWidth: 'thin' }}>
                      {items.length === 0 ? (
                        <div className="flex flex-col items-center justify-center flex-1 py-12 gap-2 opacity-45">
                          <IconInbox size={18} stroke={1.2} className="text-[var(--text-soft)]" />
                          <span className="text-2xs font-bold text-[var(--text-soft)]">{t.dashboardPage.emptyState || 'Vide'}</span>
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

/** Legacy KPI card kept for PerformancePage (imports { KpiCard } from './DashboardPage'). */
export function KpiCard({ title, value, subtitle, Icon, color: _color, trend }: { title: string; value: string | number; subtitle: string; Icon: any; color?: string; trend?: string }) {
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
