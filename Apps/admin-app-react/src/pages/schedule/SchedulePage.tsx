
import { useState, useMemo } from 'react';
import {
  IconChevronLeft, IconChevronRight,
  IconCheck, IconRoute, IconAlertTriangle, IconUsers,
  IconChartPie, IconClock, IconTrendingUp, IconInbox,
} from '@tabler/icons-react';
import { RefreshButton } from '@/components/ui/RefreshButton';
import { DraggableWidgetGrid } from '@/components/layout/DraggableWidgetGrid';
import { cn } from '@/lib/utils';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { KPICard } from '@/components/ui/kpi-card';
import { SectionCard } from '@/components/ui/section-card';
import { ProgressCircle } from '@/components/ui/progress-circle';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import {
  Table, TableHeader, TableBody, TableRow, TableHead, TableCell,
} from '@/components/ui/table';
import { useT } from '@/lib/i18n/LocaleContext';
import { useRoutes, type RouteItem } from '@/hooks/useRoutes';
import { useFleetDrivers } from '@/hooks/useVehicles';

// ── Helpers ─────────────────────────────────────────────────────────────────

function isoDate(d: Date) { return d.toISOString().slice(0, 10); }

function getWeekDays(offset: number): Date[] {
  const now = new Date();
  const day = now.getDay() || 7;
  const mon = new Date(now);
  mon.setDate(now.getDate() - day + 1 + offset * 7);
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(mon); d.setDate(mon.getDate() + i); return d;
  });
}

function routeProgress(route: RouteItem) {
  const active = route.stops.filter(s => !['REMOVED_REPLANNED', 'REMOVED_CANCELLED'].includes(s.status));
  const done = active.filter(s => ['COMPLETED', 'FAILED', 'PARTIAL'].includes(s.status)).length;
  return { done, total: active.length };
}

const DOT: Record<string, string> = {
  IN_PROGRESS: '#F59E0B', VALIDATED: '#3B82F6', CLOSED: '#10B981',
  DRAFT: '#94A3B8', CANCELLED: '#94A3B8',
};

// ── Page ──────────────────────────────────────────────────────────────────────

export default function SchedulePage() {
  const t = useT();

  const [tab, setTab] = useState('today');
  const [weekOffset, setWeekOffset] = useState(0);

  // ── TanStack Query data fetching ──────────────────────────────────────────
  const todayIso = isoDate(new Date());
  const weekDaysComputed = useMemo(() => getWeekDays(weekOffset), [weekOffset]);
  const weekFrom = isoDate(weekDaysComputed[0]);
  const weekTo = isoDate(weekDaysComputed[weekDaysComputed.length - 1]);

  const {
    data: todayRoutes = [],
    isLoading: todayLoading,
    refetch: refetchToday,
  } = useRoutes({ from: todayIso, to: todayIso });

  const {
    data: weekRoutes = [],
    isLoading: weekLoading,
    refetch: refetchWeek,
  } = useRoutes({ from: weekFrom, to: weekTo }, tab === 'week' || weekOffset !== 0);

  const { data: allDrivers = [] } = useFleetDrivers();

  const refreshing = tab === 'today' ? todayLoading : weekLoading;

  const driversById = useMemo(() => new Map(allDrivers.map(d => [d.id, d])), [allDrivers]);
  const driverName = (id?: string) =>
    driversById.get(id ?? '')?.name ?? driversById.get(id ?? '')?.phone ?? '—';

  const handleRefresh = () => {
    if (tab === 'today') void refetchToday();
    else void refetchWeek();
  };


  const weekDays = weekDaysComputed;

  const weekStats = useMemo(() => {
    const stops = weekRoutes.flatMap(r => r.stops).filter(s => !['REMOVED_REPLANNED', 'REMOVED_CANCELLED'].includes(s.status));
    const done = stops.filter(s => ['COMPLETED', 'PARTIAL'].includes(s.status)).length;
    return {
      total: weekRoutes.length,
      closed: weekRoutes.filter(r => r.status === 'CLOSED').length,
      inProgress: weekRoutes.filter(r => r.status === 'IN_PROGRESS').length,
      rate: stops.length > 0 ? Math.round((done / stops.length) * 100) : 0,
    };
  }, [weekRoutes]);

  const todayStats = useMemo(() => {
    const stops = todayRoutes.flatMap(r => r.stops).filter(s => !['REMOVED_REPLANNED', 'REMOVED_CANCELLED'].includes(s.status));
    const totalCount = stops.length;
    const successCount = stops.filter(s => ['COMPLETED', 'PARTIAL'].includes(s.status)).length;
    const failCount = stops.filter(s => s.status === 'FAILED').length;
    const ongoingCount = stops.filter(s => !['COMPLETED', 'PARTIAL', 'FAILED'].includes(s.status)).length;
    return {
      totalCount, successCount, failCount, ongoingCount,
      rate: totalCount > 0 ? Math.round((successCount / totalCount) * 100) : 0,
      successPct: totalCount > 0 ? (successCount / totalCount) * 100 : 0,
      failPct: totalCount > 0 ? (failCount / totalCount) * 100 : 0,
      ongoingPct: totalCount > 0 ? (ongoingCount / totalCount) * 100 : 0,
    };
  }, [todayRoutes]);

  return (
    <div className="flex flex-col h-full overflow-hidden" style={{ background: 'var(--app-bg)' }}>

      {/* Tabs — title bar removed; refresh lives in the tab strip */}
      <Tabs
        value={tab}
        onValueChange={setTab}
        className="flex flex-col flex-1 overflow-hidden"
      >
        <div className="px-4 shrink-0 flex items-center" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-sm)' }}>
          <TabsList variant="line" className="h-10 bg-transparent">
            <TabsTrigger value="today" className="text-xs font-[600] text-[var(--text-muted)]">{t.operationsPage.tabToday}</TabsTrigger>
            <TabsTrigger value="week" className="text-xs font-[600] text-[var(--text-muted)]">{t.operationsPage.tabWeek}</TabsTrigger>
          </TabsList>
          <div className="ml-auto">
            <RefreshButton refreshing={refreshing} onClick={handleRefresh} />
          </div>
        </div>

        {/* ── TAB: Aujourd'hui ── */}
        <TabsContent value="today" className="flex-1 overflow-auto m-0">
          <div className="p-6 max-w-[1400px] mx-auto">
          <DraggableWidgetGrid
            storageKey="operations-today-v3"
            items={[
              {
                id: 'kpi-active-routes',
                defaultLayout: { w: 3, h: 2, x: 0, y: 0, minW: 2, minH: 2 },
                children: (
                  <KPICard
                    label={t.operationsPage.kpiActiveRoutes}
                    value={todayRoutes.filter(r => r.status === 'IN_PROGRESS' || r.status === 'VALIDATED').length}
                    icon={<IconRoute size={16} />}
                  />
                ),
              },
              {
                id: 'kpi-field-drivers',
                defaultLayout: { w: 3, h: 2, x: 3, y: 0, minW: 2, minH: 2 },
                children: (
                  <KPICard
                    label={t.operationsPage.kpiFieldDrivers}
                    value={new Set(todayRoutes.filter(r => r.status === 'IN_PROGRESS').map(r => r.driverId)).size}
                    icon={<IconUsers size={16} />}
                  />
                ),
              },
              {
                id: 'kpi-completed-stops',
                defaultLayout: { w: 3, h: 2, x: 6, y: 0, minW: 2, minH: 2 },
                children: (
                  <KPICard
                    label={t.operationsPage.kpiCompletedStops}
                    value={todayStats.successCount}
                    icon={<IconCheck size={16} />}
                    tone="success"
                  />
                ),
              },
              {
                id: 'kpi-failures',
                defaultLayout: { w: 3, h: 2, x: 9, y: 0, minW: 2, minH: 2 },
                children: (
                  <KPICard
                    label={t.operationsPage.kpiFailures}
                    value={todayStats.failCount}
                    icon={<IconAlertTriangle size={16} />}
                    tone={todayStats.failCount > 0 ? 'danger' : 'default'}
                  />
                ),
              },
              {
                id: 'section-start',
                defaultLayout: { w: 4, h: 6, x: 0, y: 2, minW: 3, minH: 4 },
                children: (
                <SectionCard
                  title={
                    <div className="flex items-center gap-2">
                      <IconTrendingUp size={15} style={{ color: 'var(--brand)' }} />
                      <span>{t.operationsPage.sectionStart}</span>
                    </div>
                  }
                  actions={
                    <Badge variant="secondary">
                      {todayRoutes.filter(r => r.status === 'VALIDATED').length}
                    </Badge>
                  }
                >
                  {todayRoutes.filter(r => r.status === 'VALIDATED').length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-8 gap-2 opacity-40">
                      <IconRoute size={24} stroke={1.5} className="text-[var(--text-muted)]" />
                      <p className="text-xs font-[500] text-[var(--text-muted)]">{t.operationsPage.noRoutesWaiting}</p>
                    </div>
                  ) : (
                    <div className="flex flex-col divide-y divide-[var(--border)]">
                      {todayRoutes.filter(r => r.status === 'VALIDATED').slice(0, 4).map(route => (
                        <button
                          key={route.id}
                          type="button"
                          onClick={() => window.open(`/routes/${route.id}`, '_blank')}
                          className="flex items-center justify-between py-2.5 hover:opacity-70 transition-opacity text-left"
                        >
                          <div>
                            <p className="text-sm font-bold text-[var(--text-primary)]">{route.name}</p>
                            <p className="text-xs text-[var(--text-muted)]">{driverName(route.driverId)}</p>
                          </div>
                          <IconChevronRight size={14} className="text-[var(--text-muted)] shrink-0" />
                        </button>
                      ))}
                    </div>
                  )}
                </SectionCard>
                ),
              },
              {
                id: 'section-watchpoints',
                defaultLayout: { w: 4, h: 6, x: 4, y: 2, minW: 3, minH: 4 },
                children: (
                <SectionCard
                  title={
                    <div className="flex items-center gap-2">
                      <IconClock size={15} style={{ color: '#EF4444' }} />
                      <span>{t.operationsPage.sectionWatchpoints}</span>
                    </div>
                  }
                  actions={
                    <Badge variant="destructive">
                      {todayRoutes.filter(r => r.status === 'IN_PROGRESS').length}
                    </Badge>
                  }
                >
                  {todayRoutes.filter(r => r.status === 'IN_PROGRESS').length === 0 ? (
                    <div className="flex flex-col items-center justify-center py-8 gap-2 opacity-40">
                      <IconCheck size={24} stroke={1.5} className="text-[var(--text-muted)]" />
                      <p className="text-xs font-[500] text-[var(--text-muted)]">{t.operationsPage.allUnderControl}</p>
                    </div>
                  ) : (
                    <div className="flex flex-col gap-4">
                      {todayRoutes
                        .filter(r => r.status === 'IN_PROGRESS')
                        .sort((a, b) => {
                          const pA = routeProgress(a); const pB = routeProgress(b);
                          return (pA.total > 0 ? pA.done / pA.total : 0) - (pB.total > 0 ? pB.done / pB.total : 0);
                        })
                        .slice(0, 3)
                        .map(route => {
                          const { done, total } = routeProgress(route);
                          const pct = total > 0 ? Math.round((done / total) * 100) : 0;
                          const color = pct < 30 ? '#EF4444' : pct < 60 ? '#F97316' : pct < 90 ? '#EAB308' : '#10B981';
                          return (
                            <div key={route.id}>
                              <div className="flex items-center justify-between mb-1.5">
                                <p className="text-sm font-bold text-[var(--text-primary)]">{route.name}</p>
                                <p className="text-sm font-bold" style={{ color }}>{pct}%</p>
                              </div>
                              <div className="h-1 rounded-full bg-[var(--border)]">
                                <div className="h-1 rounded-full transition-all" style={{ width: `${pct}%`, background: color }} />
                              </div>
                            </div>
                          );
                        })}
                    </div>
                  )}
                </SectionCard>
                ),
              },
              {
                id: 'section-performance',
                defaultLayout: { w: 4, h: 6, x: 8, y: 2, minW: 3, minH: 4 },
                children: (
              <SectionCard
                title={
                  <div className="flex items-center gap-2">
                    <IconChartPie size={15} style={{ color: '#10B981' }} />
                    <span>{t.operationsPage.sectionPerformance}</span>
                  </div>
                }
                contentClassName="flex items-center justify-center py-4"
              >
                <div className="flex items-center gap-10 flex-wrap justify-center">
                  <div className="relative">
                    <ProgressCircle
                      value={todayStats.successPct}
                      size={140}
                      strokeWidth={12}
                      showValue={false}
                    />
                    <div className="absolute inset-0 flex items-center justify-center">
                      <p className="text-2xl font-bold text-[var(--text-primary)]">{todayStats.rate}%</p>
                    </div>
                  </div>
                  <div className="flex flex-col gap-3 min-w-[160px]">
                    {[
                      { color: '#10B981', label: t.operationsPage.labelSuccess, value: todayStats.successCount },
                      { color: '#EF4444', label: t.operationsPage.labelFailures, value: todayStats.failCount },
                      { color: '#F97316', label: t.operationsPage.labelOngoing, value: todayStats.ongoingCount },
                    ].map(row => (
                      <div key={row.label} className="flex items-center justify-between gap-4">
                        <div className="flex items-center gap-2">
                          <div className="w-2.5 h-2.5 rounded-full shrink-0" style={{ background: row.color }} />
                          <p className="text-xs font-[500] text-[var(--text-muted)]">{row.label}</p>
                        </div>
                        <p className="text-base font-bold text-[var(--text-primary)]">{row.value}</p>
                      </div>
                    ))}
                  </div>
                </div>
              </SectionCard>
                ),
              },
              {
                id: 'routes-table',
                defaultLayout: { w: 12, h: 8, x: 0, y: 8, minW: 6, minH: 4 },
                children: (
            <SectionCard
              title={t.operationsPage.tableTodayRoutes}
              actions={
                <Badge variant="outline">{todayRoutes.length} {t.operationsPage.tableRoute}</Badge>
              }
              padding={false}
            >
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="text-2xs font-[600] text-[var(--text-muted)] px-5">{t.operationsPage.tableRoute}</TableHead>
                    <TableHead className="text-2xs font-[600] text-[var(--text-muted)] px-5">{t.operationsPage.tableDriver}</TableHead>
                    <TableHead className="text-2xs font-[600] text-[var(--text-muted)] px-5">{t.operationsPage.tableStatus}</TableHead>
                    <TableHead className="text-2xs font-[600] text-[var(--text-muted)] px-5">{t.operationsPage.tableProgress}</TableHead>
                    <TableHead className="px-5" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {todayLoading ? (
                    Array.from({ length: 4 }).map((_, i) => (
                      <TableRow key={i}>
                        {Array.from({ length: 5 }).map((_, j) => (
                          <TableCell key={j} className="px-5 py-3">
                            <div className="h-3 rounded skeleton" style={{ width: j === 0 ? 120 : j === 4 ? 20 : 80 }} />
                          </TableCell>
                        ))}
                      </TableRow>
                    ))
                  ) : todayRoutes.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={5}>
                        <div className="flex flex-col items-center justify-center py-16 gap-2 opacity-40">
                          <IconInbox size={28} stroke={1.5} className="text-[var(--text-muted)]" />
                          <p className="text-xs font-[500] text-[var(--text-muted)]">{t.operationsPage.noRoutes}</p>
                        </div>
                      </TableCell>
                    </TableRow>
                  ) : (
                    todayRoutes.map(route => {
                      const { done, total } = routeProgress(route);
                      const pct = total > 0 ? Math.round((done / total) * 100) : 0;
                      const barColor = route.status === 'CLOSED' ? '#10B981' : route.status === 'IN_PROGRESS' ? '#F97316' : '#3B82F6';
                      return (
                        <TableRow
                          key={route.id}
                          className="cursor-pointer"
                          onClick={() => window.open(`/routes/${route.id}`, '_blank')}
                        >
                          <TableCell className="px-5 py-3">
                            <p className="text-sm font-bold text-[var(--text-primary)]">{route.name}</p>
                            <p className="text-xs text-[var(--text-muted)]">{route.city ?? '—'}</p>
                          </TableCell>
                          <TableCell className="px-5 py-3">
                            <p className="text-sm font-semibold text-[var(--text-primary)]">{driverName(route.driverId)}</p>
                          </TableCell>
                          <TableCell className="px-5 py-3">
                            <StatusBadge status={route.status} size="sm" />
                          </TableCell>
                          <TableCell className="px-5 py-3">
                            <div className="flex items-center gap-2">
                              <div className="flex-1 h-1 rounded-full bg-[var(--border)]" style={{ minWidth: 80 }}>
                                <div className="h-1 rounded-full" style={{ width: `${pct}%`, background: barColor }} />
                              </div>
                              <span className="text-xs font-bold text-[var(--text-primary)] tabular-nums">{done}/{total}</span>
                            </div>
                          </TableCell>
                          <TableCell className="px-5 py-3 text-right">
                            <IconChevronRight size={14} className="text-[var(--text-muted)] inline" />
                          </TableCell>
                        </TableRow>
                      );
                    })
                  )}
                </TableBody>
              </Table>
            </SectionCard>
                ),
              },
            ]}
          />
          </div>
        </TabsContent>

        {/* ── TAB: Semaine ── */}
        <TabsContent value="week" className="flex-1 overflow-hidden m-0 flex flex-col">
          {/* Week Nav */}
          <div className="flex items-center justify-between px-6 py-3 border-b border-[var(--border)] shrink-0" style={{ background: 'var(--surface)' }}>
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={() => setWeekOffset(w => w - 1)}
                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
              >
                <IconChevronLeft size={14} />
              </button>
              <p className="text-base font-bold font-mono text-[var(--text-primary)]">
                {weekDays[0].getDate()} {t.operationsPage.monthNames[weekDays[0].getMonth()]} — {weekDays[6].getDate()} {t.operationsPage.monthNames[weekDays[6].getMonth()]}
              </p>
              <button
                type="button"
                onClick={() => setWeekOffset(w => w + 1)}
                className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
              >
                <IconChevronRight size={14} />
              </button>
              {weekOffset !== 0 && (
                <Button variant="ghost" size="sm" onClick={() => setWeekOffset(0)} className="h-7 text-2xs font-[600] text-[var(--text-muted)]">
                  {t.operationsPage.thisWeek}
                </Button>
              )}
            </div>
            <div className="flex items-center gap-4">
              <span className="text-2xs font-[600] text-[var(--text-muted)]">{weekStats.total} {t.operationsPage.routesCount}</span>
              <span
                className="text-2xs font-bold font-mono"
                style={{ color: weekStats.rate >= 90 ? '#10B981' : weekStats.rate >= 70 ? '#F59E0B' : '#EF4444' }}
              >
                {weekStats.rate}% {t.operationsPage.completion}
              </span>
            </div>
          </div>

          {weekLoading ? (
            <div className="flex-1 flex items-center justify-center">
              <svg className="animate-spin h-6 w-6" style={{ color: 'var(--brand)' }} viewBox="0 0 24 24" fill="none">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
              </svg>
            </div>
          ) : (
            <div className="flex-1 overflow-hidden p-5 flex flex-col">
              <div className="flex flex-col flex-1 rounded border overflow-hidden" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
                {/* Calendar header */}
                <div className="flex border-b border-[var(--border)]" style={{ background: 'var(--app-bg)' }}>
                  {weekDays.map((_, idx) => (
                    <div key={idx} className={cn('flex-1 py-2.5 text-center', idx < 6 && 'border-r border-[var(--border)]')}>
                      <p className="text-2xs font-[600] text-[var(--text-muted)]">{t.operationsPage.dayNames[idx]}</p>
                    </div>
                  ))}
                </div>
                {/* Calendar body */}
                <div className="flex flex-1 overflow-hidden">
                  {weekDays.map((day, idx) => {
                    const dayIso = isoDate(day);
                    const dayRoutes = weekRoutes.filter(r => r.date === dayIso);
                    const isToday = dayIso === isoDate(new Date());
                    return (
                      <div
                        key={dayIso}
                        className={cn('flex-1 flex flex-col overflow-hidden', idx < 6 && 'border-r border-[var(--border)]')}
                        style={{ background: isToday ? 'var(--brand-soft)' : 'var(--surface)' }}
                      >
                        <div className="flex items-center justify-between px-2 py-1.5">
                          <Badge variant={isToday ? 'default' : 'secondary'} className="text-2xs h-4 px-1">
                            {dayRoutes.length}
                          </Badge>
                          <p className="text-md font-bold" style={{ color: isToday ? 'var(--brand)' : 'var(--text-primary)' }}>
                            {day.getDate()}
                          </p>
                        </div>
                        <div className="flex-1 overflow-y-auto px-1.5 pb-2 flex flex-col gap-1">
                          {dayRoutes.map(route => {
                            const { done, total } = routeProgress(route);
                            const dot = DOT[route.status] ?? '#94A3B8';
                            return (
                              <button
                                key={route.id}
                                type="button"
                                onClick={() => window.open(`/routes/${route.id}`, '_blank')}
                                className="w-full text-left p-1.5 rounded border hover:shadow-sm transition-all"
                                style={{ borderColor: 'var(--border)', borderLeft: `3px solid ${dot}`, background: isToday ? 'var(--surface)' : 'var(--app-bg)' }}
                              >
                                <p className="text-2xs font-bold text-[var(--text-primary)] truncate">{driverName(route.driverId)}</p>
                                <p className="text-2xs text-[var(--text-muted)] truncate mb-1">{route.name}</p>
                                <div className="h-[3px] rounded-full bg-[var(--border)]">
                                  <div className="h-[3px] rounded-full" style={{ width: `${total > 0 ? Math.round((done / total) * 100) : 0}%`, background: route.status === 'CLOSED' ? '#10B981' : '#F97316' }} />
                                </div>
                              </button>
                            );
                          })}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          )}

          {/* Week footer */}
          <div className="px-6 py-2 border-t border-[var(--border)] shrink-0 flex items-center gap-5" style={{ background: 'var(--surface)' }}>
            {[
              { c: '#F59E0B', label: `${weekStats.inProgress} ${t.operationsPage.weekInProgress}` },
              { c: '#10B981', label: `${weekStats.closed} ${t.operationsPage.weekClosed}` },
              { c: '#A1A1AA', label: `${weekStats.total} ${t.operationsPage.weekTotal}` },
            ].map(s => (
              <div key={s.label} className="flex items-center gap-1.5">
                <div className="w-2 h-2 rounded-full" style={{ background: s.c }} />
                <p className="text-2xs font-[600] text-[var(--text-muted)]">{s.label}</p>
              </div>
            ))}
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}

