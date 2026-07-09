import { useMemo } from 'react';
import {
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid,
} from 'recharts';
import {
  IconChartBar, IconPackage, IconAlertTriangle, IconArrowUpRight, IconRoute, IconMapPin,
  IconChevronRight, IconLayoutKanban, IconActivity,
} from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { createRouteColorMap, routeColorFromMap } from '@/lib/utils';
import { formatElapsed } from '@/lib/sla';
import { dispatchDeskQueueLink } from '@/lib/api/dispatch-link';
import { cn } from '@/lib/utils';
import { StatusBadge } from '@/components/data-display/StatusBadge';
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

// ── Enterprise KPI cards ────────────────────────────────────────────────────
const TONE_C: Record<string, string> = {
  success: 'var(--success)', warning: 'var(--warning)', danger: 'var(--danger)',
  info: 'var(--info)', brand: 'var(--brand)', default: 'var(--text-muted)',
};

const TONE_LABEL: Record<string, string> = {
  success: 'Nominal', warning: 'Attention', danger: 'Critique',
  info: 'Info', brand: 'Opérationnel', default: '—',
};

function Spark({ data, color, area }: { data: number[]; color: string; area?: boolean }) {
  if (!data || data.length < 2) return null;
  const max = Math.max(...data), min = Math.min(...data), rng = (max - min) || 1;
  const pts = data.map((v, i) => `${(i / (data.length - 1)) * 100},${24 - ((v - min) / rng) * 20 - 2}`).join(' ');
  return (
    <svg viewBox="0 0 100 24" preserveAspectRatio="none" width="100%" height="24" aria-hidden="true">
      {area && <polygon points={`0,24 ${pts} 100,24`} fill={color} opacity="0.07" />}
      <polyline points={pts} fill="none" stroke={color} strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

function DeltaPill({ delta, caption, goodWhen = 'up', format }: { delta?: number | null; caption?: string; goodWhen?: 'up' | 'down'; format?: (n: number) => string }) {
  if (delta == null) return null;
  const flat = delta === 0, up = delta > 0;
  const good = flat ? null : (goodWhen === 'up' ? up : !up);
  const color = good == null ? 'var(--text-muted)' : good ? 'var(--success)' : 'var(--danger)';
  const txt = format ? format(Math.abs(delta)) : String(Math.abs(delta));
  return (
    <span className="text-2xs font-[600] inline-flex items-center gap-0.5" style={{ color }}>
      {!flat && <span aria-hidden="true">{up ? '▲' : '▼'}</span>}{txt}
      {caption && <span className="text-[var(--text-soft)] font-normal ms-0.5">{caption}</span>}
    </span>
  );
}

interface KpiCommon {
  label: string; value: React.ReactNode; tone?: string;
  delta?: number | null; deltaCaption?: string; deltaGood?: 'up' | 'down';
  deltaFormat?: (n: number) => string; onClick?: () => void;
  comparison?: string; spark?: number[];
}

/** SLA card — sparkline as hero + thin bar + context. No dots, no uppercase. */
export function RadialKpiCard({ label, value, pct, tone = 'default', delta, deltaCaption, deltaGood = 'up', deltaFormat, comparison, onClick, spark }: KpiCommon & { pct: number }) {
  const c = TONE_C[tone] ?? TONE_C.default;
  return (
    <div className={cn('card @container h-full flex flex-col gap-2 ps-12 pe-4 py-3.5', onClick && 'cursor-pointer hover:bg-[var(--hover-bg)]')} onClick={onClick}>
      <span className="text-2xs text-[var(--text-muted)]">{label}</span>
      <div className="font-mono font-bold tabular-nums leading-none text-[var(--text-primary)] tracking-tight" style={{ fontSize: 'clamp(1.5rem, 5cqi, 2.5rem)' }}>{value}</div>
      {spark && spark.length > 1 && <div className="h-5 w-full opacity-80"><Spark data={spark} color={c} area /></div>}
      {!spark && <div className="relative h-1 bg-[var(--hover-bg)] rounded-full overflow-hidden">
        <div className="absolute inset-y-0 left-0 rounded-full transition-all duration-500" style={{ width: `${Math.max(0, Math.min(100, pct))}%`, background: c }} />
      </div>}
      <div className="flex items-center justify-between gap-2">
        {comparison && <span className="text-2xs text-[var(--text-muted)]">{comparison}</span>}
        <DeltaPill delta={delta} caption={deltaCaption} goodWhen={deltaGood} format={deltaFormat} />
      </div>
    </div>
  );
}

/** Ratio card — sparkline or bar + inline label. No dots, no uppercase. */
export function BulletKpiCard({ label, value, sub, ratioPct, tone = 'info', delta, deltaCaption, deltaGood = 'up', deltaFormat, comparison, spark }: KpiCommon & { sub?: string; ratioPct: number }) {
  const c = TONE_C[tone] ?? TONE_C.info;
  return (
    <div className="card @container h-full flex flex-col gap-2 ps-12 pe-4 py-3.5">
      <div className="flex items-baseline gap-1.5">
        <span className="font-mono font-bold tabular-nums leading-none text-[var(--text-primary)] tracking-tight" style={{ fontSize: 'clamp(1.5rem, 5cqi, 2.5rem)' }}>{value}</span>
        {sub && <span className="text-sm text-[var(--text-muted)] font-normal">{sub}</span>}
      </div>
      <span className="text-2xs text-[var(--text-muted)]">{label}</span>
      {spark && spark.length > 1 ? (
        <div className="h-5 w-full opacity-80"><Spark data={spark} color={c} area /></div>
      ) : (
        <div className="relative h-2 bg-[var(--hover-bg)] rounded-full overflow-hidden">
          <div className="absolute inset-y-0 left-0 rounded-full transition-all duration-500" style={{ width: `${Math.max(0, Math.min(100, ratioPct))}%`, background: c }} />
        </div>
      )}
      <div className="flex items-center justify-between gap-2">
        {comparison && <span className="text-2xs text-[var(--text-muted)]">{comparison}</span>}
        <DeltaPill delta={delta} caption={deltaCaption} goodWhen={deltaGood} format={deltaFormat} />
      </div>
    </div>
  );
}

/** Trend card — sparkline as hero, not decoration. No dots, no uppercase. */
export function SparkKpiCard({ label, value, spark, tone = 'default', delta, deltaCaption, deltaGood = 'down', deltaFormat, comparison }: KpiCommon & { spark: number[] }) {
  const c = TONE_C[tone] ?? TONE_C.default;
  return (
    <div className="card @container h-full flex flex-col gap-1 ps-12 pe-4 py-3.5">
      <div className="flex items-baseline gap-1.5">
        <span className="font-mono font-bold tabular-nums leading-none text-[var(--text-primary)] tracking-tight" style={{ fontSize: 'clamp(1.5rem, 5cqi, 2.5rem)' }}>{value}</span>
        <span className="text-2xs text-[var(--text-muted)]">{label}</span>
      </div>
      <div className="flex-1 min-h-0 w-full"><Spark data={spark} color={c} area /></div>
      <div className="flex items-center justify-between gap-2">
        {comparison && <span className="text-2xs text-[var(--text-muted)]">{comparison}</span>}
        <DeltaPill delta={delta} caption={deltaCaption} goodWhen={deltaGood} format={deltaFormat} />
      </div>
    </div>
  );
}

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

const fmtCycle = (m: number): string => {
  if (!m || m <= 0) return '0 m';
  if (m < 60) return `${Math.round(m)} m`;
  const h = Math.floor(m / 60), r = Math.round(m % 60);
  return r > 0 ? `${h} h ${r} m` : `${h} h`;
};

/** Migrated from the old Analyse page: global cycle time broken into phases (assign→pickup→transit). */
export function CycleTimeWidget({ stats }: { stats: DashboardStats | null }) {
  const t = useT();
  const today = stats?.today;
  const a = today?.avgAssignToPickupMinutes ?? 0;
  const b = today?.avgPickupToTransitMinutes ?? 0;
  const c = today?.avgTransitToCompletionMinutes ?? 0;
  const total = a + b + c;
  const phases = [
    { label: t.performancePage.driverResponse, sub: t.performancePage.assignmentToPickup, m: a, color: 'var(--brand)' },
    { label: t.performancePage.depotLoading, sub: t.performancePage.pickupToTransit, m: b, color: 'var(--text-soft)' },
    { label: t.performancePage.effectiveTransit, sub: t.performancePage.transitToCompletion, m: c, color: 'var(--text-muted)' },
  ];
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center gap-2 border-b border-[var(--border)] shrink-0">
        <IconActivity size={15} className="text-[var(--brand)]" />
        <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">{t.performancePage.temporalFragmentation}</span>
      </div>
      <div className="p-4 flex flex-col gap-4 flex-1 justify-center">
        {phases.map((p, i) => (
          <div key={i} className="flex flex-col gap-1.5">
            <div className="flex items-center justify-between leading-none">
              <div className="flex flex-col gap-0.5 min-w-0">
                <span className="text-xs font-[500] text-[var(--text-primary)]">{p.label}</span>
                <span className="text-2xs text-[var(--text-muted)] truncate">{p.sub}</span>
              </div>
              <span className="text-sm font-[500] font-mono text-[var(--text-primary)] shrink-0">{fmtCycle(p.m)}</span>
            </div>
            <div className="h-[3px] w-full bg-[var(--hover-bg)] overflow-hidden rounded-full">
              <div className="h-full rounded-full transition-all duration-300" style={{ width: `${total > 0 ? (p.m / total) * 100 : 0}%`, background: p.color }} />
            </div>
          </div>
        ))}
        <div className="mt-1 p-2.5 rounded-md flex justify-between items-center leading-none" style={{ background: 'var(--hover-bg)', border: '1px solid var(--border)' }}>
          <span className="text-xs font-[600] text-[var(--text-secondary)]">{t.performancePage.totalCycleIndex}</span>
          <span className="text-md font-[600] font-mono text-[var(--brand)]">{fmtCycle(total)}</span>
        </div>
      </div>
    </div>
  );
}

/** Migrated from the old Analyse page: order volume by zone. */
export function ZoneDensityWidget({ kpi }: { kpi: { ordersByZone?: Record<string, number> } | null }) {
  const t = useT();
  const entries = useMemo(
    () => Object.entries(kpi?.ordersByZone ?? {}).sort((a, b) => b[1] - a[1]).slice(0, 8),
    [kpi],
  );
  const max = Math.max(...entries.map(e => e[1]), 1);
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center gap-2 border-b border-[var(--border)] shrink-0">
        <IconMapPin size={15} className="text-[var(--brand)]" />
        <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">{t.performancePage.densityByZone}</span>
      </div>
      <div className="p-4 flex flex-col gap-3 flex-1 overflow-y-auto">
        {entries.length === 0 ? (
          <div className="flex-1 flex items-center justify-center text-xs text-[var(--text-muted)]">{t.dashboardPage.noData || 'Aucune donnée disponible'}</div>
        ) : entries.map(([zone, count], i) => (
          <div key={zone} className="flex flex-col gap-1">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-2xs font-mono text-[var(--text-muted)] w-4">{i + 1}</span>
                <span className="text-xs font-medium text-[var(--text-secondary)] truncate" title={zone}>{zone}</span>
              </div>
              <span className="text-xs font-mono text-[var(--text-primary)] shrink-0">{count}</span>
            </div>
            <div className="h-[2px] w-full bg-[var(--hover-bg)] overflow-hidden rounded-full">
              <div className="h-full rounded-full bg-[var(--brand)]" style={{ width: `${(count / max) * 100}%` }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/** Dense status decomposition of the current window (stacked bar + legend). */
export function StatusBreakdownWidget({ stats }: { stats: DashboardStats | null }) {
  const t = useT();
  const d = stats?.today;
  const rows = [
    { key: 'waiting', label: t.statusLabels?.UNSCHEDULED ?? 'En attente', v: d?.waiting ?? 0, c: 'var(--text-muted)' },
    { key: 'assigned', label: t.statusLabels?.SCHEDULED ?? 'Planifié', v: d?.assigned ?? 0, c: 'var(--info)' },
    { key: 'transit', label: t.statusLabels?.IN_TRANSIT ?? 'En transit', v: d?.inTransit ?? 0, c: 'var(--warning)' },
    { key: 'delivered', label: t.statusLabels?.DELIVERED ?? 'Livré', v: d?.delivered ?? 0, c: 'var(--success)' },
    { key: 'failed', label: t.statusLabels?.FAILED ?? 'Échec', v: d?.failed ?? 0, c: 'var(--danger)' },
  ];
  const total = rows.reduce((s, r) => s + r.v, 0) || 1;
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center gap-2 border-b border-[var(--border)] shrink-0">
        <IconChartBar size={15} className="text-[var(--brand)]" />
        <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">{t.dashboardPage.statusBreakdown ?? 'Répartition par statut'}</span>
      </div>
      <div className="p-4 flex items-center gap-4 flex-1">
        <svg width="92" height="92" viewBox="0 0 42 42" className="shrink-0" aria-hidden="true">
          <circle cx="21" cy="21" r="15.915" fill="none" stroke="var(--hover-bg)" strokeWidth="5" />
          {(() => { let acc = 0; return rows.filter(r => r.v > 0).map(r => { const len = (r.v / total) * 100; const off = -acc; acc += len; return <circle key={r.key} cx="21" cy="21" r="15.915" fill="none" stroke={r.c} strokeWidth="5" strokeDasharray={`${len} ${100 - len}`} strokeDashoffset={off} transform="rotate(-90 21 21)" />; }); })()}
          <text x="21" y="20.5" textAnchor="middle" fill="var(--text-primary)" fontFamily="var(--font-mono)" fontSize="7" fontWeight="600">{rows.reduce((s, r) => s + r.v, 0)}</text>
          <text x="21" y="26" textAnchor="middle" fill="var(--text-muted)" fontSize="3.4">total</text>
        </svg>
        <div className="flex-1 grid grid-cols-1 gap-y-1.5 min-w-0">
          {rows.map(r => (
            <div key={r.key} className="flex items-center justify-between text-xs">
              <span className="flex items-center gap-1.5 min-w-0">
                <span className="w-2 h-2 rounded-sm shrink-0" style={{ background: r.c }} />
                <span className="text-[var(--text-secondary)] truncate">{r.label}</span>
              </span>
              <span className="font-mono text-[var(--text-primary)] shrink-0">{r.v}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

/** Compact ops-churn counters: reassignments, replans, at-risk, breached. */
export function OpsCountersWidget({ kpi, ops }: { kpi: { totalReassigned?: number; totalReplanned?: number } | null; ops: { sla?: { slaAtRisk?: number; slaBreached?: number } } | null }) {
  const t = useT();
  const cells = [
    { label: t.dashboardPage.opsReassigned ?? 'Réassignations', v: kpi?.totalReassigned ?? 0, tone: 'var(--text-primary)' },
    { label: t.dashboardPage.opsReplanned ?? 'Replanifications', v: kpi?.totalReplanned ?? 0, tone: 'var(--text-primary)' },
    { label: t.dashboardPage.opsAtRisk ?? 'À risque', v: ops?.sla?.slaAtRisk ?? 0, tone: 'var(--warning)' },
    { label: t.dashboardPage.opsBreached ?? 'En dépassement', v: ops?.sla?.slaBreached ?? 0, tone: 'var(--danger)' },
  ];
  return (
    <div className="card overflow-hidden flex flex-col h-full">
      <div className="ps-10 pe-5 py-3 flex items-center gap-2 border-b border-[var(--border)] shrink-0">
        <IconActivity size={15} className="text-[var(--brand)]" />
        <span className="text-xs font-semibold uppercase tracking-wider text-[var(--text-primary)]">{t.dashboardPage.opsCounters ?? 'Turbulence dispatch'}</span>
      </div>
      <div className="grid grid-cols-2 flex-1">
        {cells.map((c, i) => (
          <div key={c.label} className="flex flex-col justify-center gap-0.5 p-4" style={{ borderTop: i >= 2 ? '0.5px solid var(--border)' : undefined, borderInlineStart: i % 2 === 1 ? '0.5px solid var(--border)' : undefined }}>
            <span className="font-mono text-2xl font-semibold tabular-nums" style={{ color: c.tone }}>{c.v}</span>
            <span className="text-2xs text-[var(--text-muted)]">{c.label}</span>
          </div>
        ))}
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
