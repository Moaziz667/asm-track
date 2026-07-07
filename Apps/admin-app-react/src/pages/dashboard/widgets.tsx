import { useMemo } from 'react';
import {
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';
import {
  IconChartBar, IconPackage, IconAlertTriangle, IconArrowUpRight, IconRoute, IconMapPin,
  IconChevronRight, IconLayoutKanban,
} from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { createRouteColorMap, routeColorFromMap } from '@/lib/utils';
import { formatElapsed } from '@/lib/sla';
import { dispatchDeskQueueLink } from '@/lib/dispatch-link';
import { cn } from '@/lib/utils';
import StatusBadge from '@/components/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { SectionCard } from '@/components/ui/section-card';
import { Badge } from '@/components/ui/badge';
import type { DashboardStats } from '@/types';

// Minimal shapes for the dashboard aggregates (server sends loose summary rows).
type AttentionItem = {
  deliveryId?: string; orderId?: string; orderRef?: string; clientName?: string;
  driverName?: string; city?: string; status?: string; slaHealth?: string;
  scheduledAt?: string; createdAt?: string;
};
type DriverLite = { name?: string; driverName?: string };
type RouteLite = { id: string; name?: string; status?: string; driverId?: string; totalStops?: number; stops?: unknown[] };

export function TrendChartWidget({ trend }: { trend: Array<{ count: number; delivered: number; failed: number }> }) {
  const t = useT();
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
        <div className="flex items-center gap-2">
          <IconChartBar size={15} className="text-[var(--brand)]" />
          <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">{t.performancePage.volumeCurve}</span>
        </div>
        <span className="text-2xs font-medium text-[var(--text-muted)]">{t.performancePage.lastSevenDays}</span>
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

export function NeedsAttentionWidget({ items, navigate }: { items: AttentionItem[]; navigate: (p: string) => void }) {
  const t = useT();
  const { locale } = useLocaleStore();
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
        <div className="flex items-center gap-2">
          <IconAlertTriangle size={15} className="text-[var(--danger)]" />
          <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">{t.dashboardPage.needsAttention || 'Needs Attention'}</span>
          <span className="text-2xs font-bold px-1.5 py-0.5 rounded bg-[var(--danger-bg)] text-[var(--danger)] font-mono leading-none">{items.length}</span>
        </div>
        <button onClick={() => navigate('/dispatch-desk?tab=queue')} className="text-xs font-medium text-[var(--brand)] hover:underline flex items-center gap-1 cursor-pointer transition-colors">
          {t.dashboardPage.needsAttentionViewAll || 'View all'} <IconArrowUpRight size={11} />
        </button>
      </div>
      <div className="flex-1 overflow-y-auto" style={{ scrollbarWidth: 'thin' }}>
        <div className="divide-y divide-[var(--border)]">
          {items.map((exc, idx) => {
            const timeRef = exc.scheduledAt || exc.createdAt;
            const timeStr = timeRef ? formatElapsed(timeRef, locale) : '—';
            return (
              <div
                key={idx}
                onClick={() => navigate(dispatchDeskQueueLink({ orderRef: exc.orderRef, orderId: exc.orderId, deliveryId: exc.deliveryId }))}
                className="flex items-stretch hover:bg-[var(--hover-bg)] transition-colors cursor-pointer overflow-hidden group border-b border-[var(--border)] last:border-0"
              >
                <div className="flex flex-col min-w-0 flex-1 gap-1 p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-xs font-semibold text-[var(--text-primary)] truncate leading-tight">
                      {exc.clientName || '—'}{exc.city ? <span className="font-normal text-[var(--text-muted)]"> · {exc.city}</span> : null}
                    </span>
                    <span className="text-3xs font-mono text-[var(--text-soft)] shrink-0">{timeStr}</span>
                  </div>
                  <div className="flex items-center gap-1.5 flex-wrap">
                    {exc.orderRef && <span className="font-mono text-2xs text-[var(--brand)]">{exc.orderRef}</span>}
                    <StatusBadge status={exc.status ?? ""} size="sm" />
                    <SlaHealthBadge health={exc.slaHealth} />
                    {exc.driverName && <span className="text-3xs text-[var(--text-muted)] truncate">· {exc.driverName}</span>}
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

export function TopItemsWidget({ stats }: { stats: DashboardStats | null }) {
  const t = useT();
  if (!stats) return null;
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
        <div className="flex items-center gap-2">
          <IconPackage size={15} className="text-[var(--brand)]" />
          <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">{t.dashboardPage.topItemsTitle || 'Top articles livrés'}</span>
        </div>
      </div>
      <div className="p-4 flex flex-col gap-2.5 flex-1 overflow-y-auto">
        {!stats?.topItems || stats.topItems.length === 0 ? (
          <div className="flex-1 flex items-center justify-center text-xs text-[var(--text-muted)]">{t.dashboardPage.noData || 'Aucune donnée disponible'}</div>
        ) : (() => {
          const maxCount = Math.max(...stats.topItems.map((item) => item.count), 1);
          return stats.topItems.map((item, idx) => (
            <div key={item.sku} className="flex flex-col gap-1">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 min-w-0">
                  <span className="text-2xs font-mono text-[var(--text-muted)] w-4">{idx + 1}</span>
                  <span className="text-xs font-medium text-[var(--text-secondary)] truncate" title={item.name}>
                    {item.name} <span className="text-2xs text-[var(--text-muted)]">({item.sku})</span>
                  </span>
                </div>
                <span className="text-xs font-mono text-[var(--text-primary)] shrink-0">{item.count} u</span>
              </div>
              <div className="h-[2px] w-full bg-[var(--hover-bg)] overflow-hidden rounded-full">
                <div className="h-full transition-all duration-300 rounded-full bg-[var(--brand)]" style={{ width: `${(item.count / maxCount) * 100}%` }} />
              </div>
            </div>
          ));
        })()}
      </div>
    </div>
  );
}

export function FailureCausesWidget({ stats }: { stats: DashboardStats | null }) {
  const t = useT();
  if (!stats) return null;
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center justify-between border-b border-[var(--border)] shrink-0">
        <div className="flex items-center gap-2">
          <IconAlertTriangle size={15} className="text-[var(--danger)]" />
          <span className="text-xs font-semibold" style={{ color: 'var(--text-primary)' }}>{t.dashboardPage.failureCausesTitle || "Top causes d'échec"}</span>
        </div>
      </div>
      <div className="p-4 flex flex-col gap-3 flex-1 overflow-hidden min-h-0">
        {!stats?.byFailureCode || stats.byFailureCode.length === 0 ? (
          <div className="flex-1 flex items-center justify-center text-xs text-[var(--text-muted)]">{t.dashboardPage.noData || 'Aucune donnée disponible'}</div>
        ) : (() => {
          const totalFailures = stats.byFailureCode.reduce((acc: number, curr) => acc + curr.count, 0) || 1;
          const data = stats.byFailureCode.map((fail) => ({ name: t.failureCodes?.[fail.code] || fail.code, value: fail.count, pct: (fail.count / totalFailures) * 100 }));
          return data.map((item) => (
            <div key={item.name} className="flex flex-col gap-1">
              <div className="flex items-center justify-between text-xs">
                <span className="text-[var(--text-secondary)] font-medium truncate" title={item.name}>{item.name}</span>
                <span className="font-mono text-[var(--text-muted)] font-[500] shrink-0">{item.value} <span className="text-2xs">({item.pct.toFixed(0)}%)</span></span>
              </div>
              <div className="h-[3px] w-full bg-[var(--hover-bg)] overflow-hidden rounded-full">
                <div className="h-full transition-all duration-300 rounded-full bg-[var(--danger)]" style={{ width: `${item.pct}%` }} />
              </div>
            </div>
          ));
        })()}
      </div>
    </div>
  );
}

export function DriverAvailabilityWidget({ driverGroups }: { driverGroups: { online: DriverLite[]; onBreak: DriverLite[]; offline: DriverLite[] } }) {
  const t = useT();
  return (
    <div className="card p-4 h-full flex flex-col">
      <span className="ps-10 text-xs font-semibold uppercase tracking-wider text-[var(--text-muted)] block mb-3">{t.dashboardPage.driverAvailability || 'Fleet Status'}</span>
      <div className="flex flex-col gap-2 overflow-y-auto">
        {[
          { group: driverGroups.online, label: t.dashboardPage.driverOnline || 'Online', tone: 'text-[var(--success)]' },
          { group: driverGroups.onBreak, label: t.dashboardPage.driverOnBreak || 'On Break', tone: 'text-[var(--warning)]' },
          { group: driverGroups.offline, label: t.dashboardPage.driverOffline || 'Offline', tone: 'text-[var(--text-muted)]' },
        ].map(({ group, label, tone }) => (
          <div key={label} className="flex items-start gap-2">
            <span className={cn('text-xs font-bold shrink-0 min-w-[80px]', tone)}>{label} <span className="font-mono text-[var(--text-muted)]">({group.length})</span></span>
            {group.length > 0 && (
              <div className="flex flex-wrap gap-1 flex-1">
                {group.slice(0, 8).map((d, i) => (
                  <span key={i} className="text-2xs font-medium px-1.5 py-0.5 rounded bg-[var(--hover-bg)] text-[var(--text-secondary)] border border-[var(--border)] truncate max-w-[100px]">{d.name || d.driverName || '?'}</span>
                ))}
                {group.length > 8 && <span className="text-2xs font-bold text-[var(--text-soft)] px-1 py-0.5">+{group.length - 8}</span>}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

export function ActiveRoutesWidget({ activeRoutes, focusedRouteId, setFocusedRouteId, driverName }: {
  activeRoutes: RouteLite[]; focusedRouteId: string | null; setFocusedRouteId: (id: string | null) => void; driverName: (id?: string) => string;
}) {
  const t = useT();
  const routeColorMap = useMemo(() => createRouteColorMap(activeRoutes), [activeRoutes]);
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
            const color = routeColorFromMap(routeColorMap, route.id);
            const focused = focusedRouteId === route.id;
            const stopCount = route.totalStops ?? route.stops?.length ?? 0;
            return (
              <button
                key={route.id}
                type="button"
                onClick={() => setFocusedRouteId(focused ? null : route.id)}
                className="flex items-center gap-2.5 py-2 px-2 rounded-md text-left transition-colors"
                style={{ background: focused ? 'var(--hover-bg)' : 'transparent' }}
                title={t.dashboardPage?.focusOnMap || 'Centrer sur la carte'}
              >
                <IconRoute size={14} style={{ color }} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <p className="text-sm font-bold text-[var(--text-primary)] truncate">{route.name}</p>
                    <StatusBadge status={route.status ?? ""} size="sm" />
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
      <span className="ps-10 text-xs font-semibold uppercase tracking-wider text-[var(--text-muted)] block mb-3">{t.dashboardPage.quickActions || 'Quick Actions'}</span>
      <div className="grid grid-cols-2 gap-2 flex-1 min-h-0">
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
            className="flex items-center gap-2 px-3 py-2 rounded-md border border-[var(--border)] bg-[var(--surface)] hover:bg-[var(--hover-bg)] transition-colors cursor-pointer text-left active:scale-[0.98] group"
          >
            <Icon size={14} className="text-[var(--text-muted)] shrink-0" strokeWidth={1.8} />
            <span className="text-xs font-medium text-[var(--text-secondary)] leading-tight">{label}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
