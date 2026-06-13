import {
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid, PieChart, Pie, Cell,
} from 'recharts';
import {
  IconChartBar, IconPackage, IconAlertTriangle, IconArrowUpRight, IconRoute, IconMapPin,
  IconChevronRight, IconLayoutKanban,
} from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { routeColor } from '@/lib/utils';
import { formatElapsed } from '@/lib/sla';
import { dispatchDeskQueueLink } from '@/lib/dispatch-link';
import StatusBadge from '@/components/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { SectionCard } from '@/components/ui/section-card';
import { Badge } from '@/components/ui/badge';

export function TrendChartWidget({ trend }: { trend: Array<{ count: number; delivered: number; failed: number }> }) {
  const t = useT();
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="pl-10 pr-5 py-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
        <div className="flex items-center gap-2">
          <IconChartBar size={16} style={{ color: 'var(--brand)' }} />
          <span className="text-xs font-[600]" style={{ color: 'var(--text-primary)' }}>{t.performancePage.volumeCurve}</span>
        </div>
        <span className="text-xs font-medium" style={{ color: 'var(--text-muted)' }}>{t.performancePage.lastSevenDays}</span>
      </div>
      <div className="p-4 flex-1 min-h-0 w-full">
        {trend.length === 0 ? (
          <div className="flex items-center justify-center h-full opacity-40">
            <span className="text-xs">{t.dashboardPage.noData || 'Aucune donnée disponible'}</span>
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={trend} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
              <defs>
                <linearGradient id="colorVolume" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor="var(--brand)" stopOpacity={0.25} />
                  <stop offset="95%" stopColor="var(--brand)" stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" />
              <XAxis dataKey="date" tickFormatter={(v) => v ? v.split('-').slice(1).reverse().join('/') : ''} tick={{ fontSize: 10, fontWeight: 500, fill: 'var(--text-secondary)' }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fontSize: 10, fontWeight: 500, fill: 'var(--text-secondary)' }} axisLine={false} tickLine={false} />
              <Tooltip
                cursor={{ stroke: 'var(--border)', strokeWidth: 1 }}
                contentStyle={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: '4px', padding: '8px 12px', color: 'var(--text-primary)', fontSize: 11 }}
                labelStyle={{ color: 'var(--text-secondary)', fontSize: '10px', fontWeight: 500, marginBottom: '4px' }}
                itemStyle={{ color: 'var(--text-primary)', fontSize: '12px', fontWeight: 600, fontFamily: 'monospace' }}
                formatter={(value) => [`${value}`, t.performancePage.volume || 'Volume']}
              />
              <Area type="monotone" dataKey="count" stroke="var(--brand)" strokeWidth={2} fillOpacity={1} fill="url(#colorVolume)" activeDot={{ r: 4, strokeWidth: 0, fill: 'var(--brand)' }} />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}

export function NeedsAttentionWidget({ items, navigate }: { items: any[]; navigate: (p: string) => void }) {
  const t = useT();
  const { locale } = useLocaleStore();
  return (
    <div className="flex flex-col bg-[var(--danger-bg)] dark:bg-[var(--danger)]/10 border border-[var(--danger)]/30 rounded-xl h-full shadow-sm overflow-hidden relative">
      <div className="absolute top-0 left-0 right-0 h-[3px] bg-[var(--danger)]" />
      <div className="ps-8 pe-4 py-3 border-b border-[var(--border)] flex items-center justify-between shrink-0">
        <span className="flex items-center gap-2">
          <span className="text-xl font-bold text-[var(--text-primary)]">{t.dashboardPage.needsAttention || 'Needs Attention'}</span>
          <span className="text-xs font-bold px-2 py-0.5 rounded-full" style={{ background: 'var(--danger)', color: '#fff' }}>{items.length}</span>
        </span>
        <button onClick={() => navigate('/dispatch-desk?tab=queue')} className="text-xs font-medium text-[var(--brand-blue)] hover:underline flex items-center gap-1 cursor-pointer transition-colors">
          {t.dashboardPage.needsAttentionViewAll || 'View all'} <IconArrowUpRight size={11} />
        </button>
      </div>
      <div className="flex-1 overflow-y-auto px-6 py-2" style={{ scrollbarWidth: 'thin' }}>
        <div className="flex flex-col gap-3 py-2">
          {items.map((exc: any, idx: number) => {
            const exHealth = (exc.slaHealth && exc.slaHealth !== 'NONE') ? exc.slaHealth : exc.slaWorstHealth;
            const isCrit = exHealth === 'BREACHED' || exHealth === 'LATE' || exc.severity === 'CRITICAL';
            const accent = isCrit ? '#C7372F' : '#D4772C';
            const timeRef = exc.scheduledAt || exc.createdAt;
            const timeStr = timeRef ? formatElapsed(timeRef, locale) : '—';
            return (
              <div
                key={idx}
                onClick={() => navigate(dispatchDeskQueueLink({ orderRef: exc.orderRef, orderId: exc.orderId, deliveryId: exc.deliveryId }))}
                className="flex items-stretch rounded-lg border bg-[var(--surface)] hover:shadow-sm transition-all cursor-pointer overflow-hidden group"
                style={{ borderColor: 'var(--border)' }}
              >
                <span className="w-[3px] shrink-0" style={{ background: accent }} />
                <div className="flex flex-col min-w-0 flex-1 gap-1.5 p-2.5">
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs font-bold" style={{ color: 'var(--brand)' }}>{exc.orderRef || exc.deliveryId?.slice(0, 8) || 'Alert'}</span>
                    <span className="text-2xs font-mono text-[var(--text-soft)] shrink-0">{timeStr}</span>
                  </div>
                  <span className="text-base font-semibold text-[var(--text-primary)] truncate leading-tight">
                    {exc.clientName || '—'}{exc.city ? <span className="font-normal text-[var(--text-muted)]"> · {exc.city}</span> : null}
                  </span>
                  <div className="flex items-center gap-1.5 flex-wrap">
                    <StatusBadge status={exc.status} size="sm" />
                    <SlaHealthBadge health={exc.slaHealth} />
                    {exc.driverName && <span className="text-2xs text-[var(--text-muted)] truncate">· {exc.driverName}</span>}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export function TopItemsWidget({ stats }: { stats: any }) {
  const t = useT();
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="pl-10 pr-5 py-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
        <div className="flex items-center gap-2">
          <IconPackage size={16} style={{ color: 'var(--brand)' }} />
          <span className="text-xs font-[600]" style={{ color: 'var(--text-primary)' }}>{t.dashboardPage.topItemsTitle || 'Top articles livrés'}</span>
        </div>
      </div>
      <div className="p-5 flex flex-col gap-3.5 flex-1 overflow-y-auto">
        {!stats?.topItems || stats.topItems.length === 0 ? (
          <div className="flex-1 flex items-center justify-center text-xs text-[var(--text-muted)]">{t.dashboardPage.noData || 'Aucune donnée disponible'}</div>
        ) : (() => {
          const maxCount = Math.max(...stats.topItems.map((item: any) => item.count), 1);
          return stats.topItems.map((item: any, idx: number) => (
            <div key={item.sku} className="flex flex-col gap-1.5">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="bg-[var(--hover-bg)] border border-[var(--border)] text-[var(--text-secondary)] w-4.5 h-4.5 flex items-center justify-center rounded-md font-mono text-2xs font-[500] shrink-0">{idx + 1}</span>
                  <span className="text-xs font-[500] text-[var(--text-secondary)] truncate" title={item.name}>
                    {item.name} <span className="text-2xs text-[var(--text-muted)]">({item.sku})</span>
                  </span>
                </div>
                <span className="text-xs font-[500] font-mono text-[var(--text-primary)] shrink-0">{item.count} u</span>
              </div>
              <div className="h-[3px] w-full bg-[var(--hover-bg)] overflow-hidden rounded-full">
                <div className="h-full transition-all duration-300 rounded-full bg-[var(--brand)]" style={{ width: `${(item.count / maxCount) * 100}%` }} />
              </div>
            </div>
          ));
        })()}
      </div>
    </div>
  );
}

export function FailureCausesWidget({ stats }: { stats: any }) {
  const t = useT();
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="pl-10 pr-5 py-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
        <div className="flex items-center gap-2">
          <IconAlertTriangle size={16} className="text-[var(--danger)]" />
          <span className="text-xs font-[600]" style={{ color: 'var(--text-primary)' }}>{t.dashboardPage.failureCausesTitle || "Top causes d'échec"}</span>
        </div>
      </div>
      <div className="p-2 flex flex-col sm:flex-row gap-5 flex-1 overflow-hidden min-h-0">
        {!stats?.byFailureCode || stats.byFailureCode.length === 0 ? (
          <div className="flex-1 flex items-center justify-center text-xs text-[var(--text-muted)]">{t.dashboardPage.noData || 'Aucune donnée disponible'}</div>
        ) : (() => {
          const totalFailures = stats.byFailureCode.reduce((acc: number, curr: any) => acc + curr.count, 0) || 1;
          const data = stats.byFailureCode.map((fail: any) => ({ name: t.failureCodes?.[fail.code] || fail.code, value: fail.count, pct: (fail.count / totalFailures) * 100 }));
          const COLORS = ['#EF4444', '#F97316', '#F59E0B', '#10B981', '#6366F1', '#8B5CF6'];
          return (
            <div className="flex-1 min-w-0 h-full relative flex items-center justify-center">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart margin={{ top: 0, right: 0, bottom: 0, left: 0 }}>
                  <Pie data={data} cx="50%" cy="50%" innerRadius="70%" outerRadius="95%" paddingAngle={2} dataKey="value">
                    {data.map((_entry: any, index: number) => <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />)}
                  </Pie>
                  <Tooltip
                    formatter={(value: any, name: any, props: any) => [`${value} (${props.payload.pct.toFixed(0)}%)`, name]}
                    contentStyle={{ background: 'var(--surface)', borderColor: 'var(--border-strong)', borderRadius: '6px', fontSize: '11px', color: 'var(--text-primary)' }}
                  />
                </PieChart>
              </ResponsiveContainer>
              <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
                <span className="text-[9px] uppercase font-bold tracking-wider text-[var(--text-muted)] font-mono">Total</span>
                <span className="text-md font-bold text-[var(--text-primary)] font-mono">{totalFailures}</span>
              </div>
            </div>
          );
        })()}
      </div>
    </div>
  );
}

export function DriverAvailabilityWidget({ driverGroups }: { driverGroups: { online: any[]; onBreak: any[]; offline: any[] } }) {
  const t = useT();
  return (
    <div className="card p-4 h-full flex flex-col">
      <span className="text-md font-bold text-[var(--text-primary)] block mb-3 pl-6 shrink-0">{t.dashboardPage.driverAvailability || 'Fleet Status'}</span>
      <div className="flex flex-col gap-3 overflow-y-auto pl-2">
        {[
          { group: driverGroups.online, label: 'Online', dotColor: '#4CAF82' },
          { group: driverGroups.onBreak, label: 'On Break', dotColor: '#D4772C' },
          { group: driverGroups.offline, label: 'Offline', dotColor: '#8A8F98' },
        ].map(({ group, label, dotColor }) => (
          <div key={label} className="flex items-start gap-3">
            <span className="w-2.5 h-2.5 rounded-full shrink-0 mt-1" style={{ backgroundColor: dotColor }} />
            <div className="flex flex-col min-w-0 flex-1">
              <span className="text-base font-bold text-[var(--text-secondary)]">{label} <span className="font-mono text-[var(--text-muted)] ml-1">({group.length})</span></span>
              {group.length > 0 && (
                <div className="flex flex-wrap gap-1.5 mt-2">
                  {group.slice(0, 8).map((d: any, i: number) => (
                    <span key={i} className="text-xs font-medium px-2 py-1 rounded bg-[var(--hover-bg)] text-[var(--text-secondary)] border border-[var(--border)] truncate max-w-[100px]">{d.name || d.driverName || '?'}</span>
                  ))}
                  {group.length > 8 && <span className="text-xs font-bold text-[var(--text-soft)] px-1 py-1">+{group.length - 8}</span>}
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function ActiveRoutesWidget({ activeRoutes, focusedRouteId, setFocusedRouteId, driverName }: {
  activeRoutes: any[]; focusedRouteId: string | null; setFocusedRouteId: (id: string | null) => void; driverName: (id?: string) => string;
}) {
  const t = useT();
  return (
    <SectionCard
      title={<div className="flex items-center gap-2"><span>{t.dashboardPage?.sectionActiveRoutes || 'Tournées actives'}</span></div>}
      actions={<Badge variant="secondary">{activeRoutes.length}</Badge>}
    >
      {activeRoutes.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-2 opacity-40">
          <IconRoute size={24} stroke={1.5} className="text-[var(--text-muted)]" />
          <p className="text-xs font-[500] text-[var(--text-muted)]">{t.dashboardPage?.noRoutesWaiting || 'Aucune tournée active'}</p>
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
                    <p className="text-sm font-bold text-[var(--text-primary)] truncate">{route.name}</p>
                    <StatusBadge status={route.status} size="sm" />
                  </div>
                  <p className="text-xs text-[var(--text-muted)] truncate">{driverName(route.driverId)} · {stopCount} {t.dashboardPage?.stopsLabel || 'arrêts'}</p>
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
  );
}

export function QuickActionsWidget({ navigate }: { navigate: (p: string) => void }) {
  const t = useT();
  return (
    <div className="card p-4 h-full flex flex-col">
      <span className="text-md font-bold text-[var(--text-primary)] block mb-3 pl-6 shrink-0">{t.dashboardPage.quickActions || 'Quick Actions'}</span>
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
            className="flex items-center gap-3 px-4 py-3 rounded-xl border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] hover:border-[var(--brand-blue)]/30 transition-all cursor-pointer text-left active:scale-[0.98] group"
          >
            <Icon size={18} className="text-[var(--text-muted)] group-hover:text-[var(--brand-blue)] shrink-0 transition-colors" strokeWidth={1.8} />
            <span className="text-base font-medium text-[var(--text-secondary)] group-hover:text-[var(--brand-blue)] leading-tight transition-colors">{label}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
