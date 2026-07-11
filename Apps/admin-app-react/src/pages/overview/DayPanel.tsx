import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { parseISO, isBefore, isSameDay, format, type Locale } from 'date-fns';
import { fr, enUS, arEG } from 'date-fns/locale';
import { useLocaleStore } from '@/lib/i18n';
import { IconPackage, IconRoute, IconAlertTriangle, IconClockHour4, IconCircleCheck } from '@tabler/icons-react';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { cn, formatMoney } from '@/lib/utils';
import type { useT } from '@/lib/i18n/LocaleContext';
import type { RouteItem } from '@/hooks/useRoutes';
import { CalDelivery, TERMINAL, startOfToday } from './shared';

interface Props {
  selected: string;
  deliveries: CalDelivery[];
  routes: RouteItem[];
  driverSlots: number; // available driver capacity for planning
  t: ReturnType<typeof useT>;
}

type Tone = 'default' | 'success' | 'warning' | 'danger' | 'info';
const TONE_VAR: Record<Tone, string> = {
  default: 'var(--text-primary)', success: 'var(--success)', warning: 'var(--warning)',
  danger: 'var(--danger)', info: 'var(--info)',
};

const DFNS_LOCALE: Record<string, Locale> = { fr, en: enUS, ar: arEG };
const dateLocale = (locale: string) => DFNS_LOCALE[locale] ?? fr;

/** Compact KPI tile sized for the side panel — no card chrome, typography only. */
function StatTile({ label, value, sub, tone = 'default' }: { label: string; value: string | number; sub?: string; tone?: Tone }) {
  return (
    <div className="flex flex-col gap-0.5 min-w-0">
        <span className="text-3xs font-medium text-[var(--text-muted)] truncate">{label}</span>
      <span className="text-sm font-semibold tabular-nums leading-tight truncate" style={{ color: TONE_VAR[tone] }} title={String(value)}>{value}</span>
      {sub && <span className="text-3xs text-[var(--text-soft)] truncate">{sub}</span>}
    </div>
  );
}

/** Vertical metric row: full label on the left, value + sub on the right — no truncation battles. */
function MetricRow({ label, value, sub, tone = 'default' }: { label: string; value: string | number; sub?: string; tone?: Tone }) {
  return (
    <div className="flex items-center justify-between gap-3 py-1.5 border-b border-[var(--border)] last:border-b-0">
      <span className="text-2xs font-medium text-[var(--text-secondary)] truncate">{label}</span>
      <div className="flex flex-col items-end min-w-0 shrink-0">
        <span className="text-sm font-semibold tabular-nums leading-tight truncate" style={{ color: TONE_VAR[tone] }} title={String(value)}>{value}</span>
        {sub && <span className="text-3xs text-[var(--text-soft)] truncate">{sub}</span>}
      </div>
    </div>
  );
}

/** Header count chip: the number is the hero, the unit label is muted. */
function HeaderStat({ icon, value, label }: { icon: React.ReactNode; value: number; label: string }) {
  return (
    <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-[var(--hover-bg)] border border-[var(--border)]">
      <span className="text-[var(--text-muted)]">{icon}</span>
      <span className="text-2xs font-bold tabular-nums text-[var(--text-primary)]">{value}</span>
      <span className="text-3xs text-[var(--text-muted)]">{label}</span>
    </span>
  );
}

/**
 * Adaptive day panel: a PAST day shows Outcomes (what happened — completion, COD, failures); TODAY or a
 * FUTURE day shows Planning (what's coming — planned load vs capacity, unassigned backlog). Today is a
 * hybrid: planning layout, but with the live completion already filled in.
 */
export function DayPanel({ selected, deliveries, routes, driverSlots, t }: Props) {
  const navigate = useNavigate();
  const { locale } = useLocaleStore();
  const day = parseISO(selected);
  const today = startOfToday();
  const isPast = isBefore(day, today) && !isSameDay(day, today);
  const isToday = isSameDay(day, today);
  const mode: 'planning' | 'outcomes' = isPast ? 'outcomes' : 'planning';

  const m = useMemo(() => {
    const total = deliveries.length;
    const done = deliveries.filter(d => TERMINAL.has(d.status)).length;
    const delivered = deliveries.filter(d => d.status === 'DELIVERED').length;
    const failed = deliveries.filter(d => d.status === 'FAILED').length;
    const unassigned = deliveries.filter(d => !d.driverId).length;
    const completionRate = total > 0 ? Math.round((delivered / total) * 100) : 0;
    const codAll = deliveries.reduce((s, d) => s + (d.totalAmount || 0), 0);
    const codCollected = deliveries.filter(d => d.status === 'DELIVERED').reduce((s, d) => s + (d.totalAmount || 0), 0);
    const weight = deliveries.reduce((s, d) => s + (d.totalWeightKg || 0), 0);
    const routesUsing = new Set(routes.map(r => r.driverId).filter(Boolean)).size;
    return { total, done, delivered, failed, unassigned, completionRate, codAll, codCollected, weight, routesUsing };
  }, [deliveries, routes]);

  const currency = deliveries[0]?.currency ?? 'TND';
  const capacityPct = driverSlots > 0 ? Math.min(Math.round((m.routesUsing / driverSlots) * 100), 100) : 0;
  const overCapacity = driverSlots > 0 && m.routesUsing > driverSlots;

  return (
    <div className="w-full lg:w-[360px] border-t lg:border-t-0 lg:border-l border-[var(--border)] bg-[var(--surface)] shrink-0 flex flex-col overflow-visible lg:overflow-hidden">
      {/* Header */}
      <div className="px-4 py-3 border-b border-[var(--border)] bg-[var(--surface)] shrink-0 flex items-center justify-between">
        <div className="min-w-0">
          <p className="text-base font-bold text-[var(--text-primary)] capitalize truncate">
            {format(day, 'EEEE d MMMM', { locale: dateLocale(locale) })}
          </p>
          <div className="flex items-center gap-1.5 mt-1.5">
            <HeaderStat icon={<IconPackage size={12} />} value={deliveries.length} label={t.overviewPage?.delAbbrev ?? 'del.'} />
            <HeaderStat icon={<IconRoute size={12} />} value={routes.length} label={t.overviewPage?.routesSuffix ?? 'routes'} />
          </div>
        </div>
        <ModeChip mode={mode} live={isToday} t={t} />
      </div>

      {/* KPI band — vertical metric list, full labels, no truncation battles */}
      <div className="px-3 py-2 border-b border-[var(--border)] shrink-0 bg-[var(--surface)]">
        {mode === 'outcomes' ? (
          <div className="flex flex-col">
            <MetricRow label={t.overviewPage?.kpiCompletion ?? 'Completion rate'} value={`${m.completionRate}%`} sub={`${m.delivered} / ${m.total}`}
              tone={m.completionRate >= 80 ? 'success' : m.completionRate >= 50 ? 'warning' : m.total > 0 ? 'danger' : 'default'} />
            <MetricRow label={t.overviewPage?.kpiCollected ?? 'Collected (COD)'} value={formatMoney(m.codCollected, currency)} sub={`/ ${formatMoney(m.codAll, currency)}`} tone="success" />
            <MetricRow label={t.overviewPage?.kpiFailures ?? 'Failures'} value={m.failed} sub={m.failed > 0 ? (t.overviewPage?.kpiFailuresSub ?? 'to review') : (t.overviewPage?.kpiNoFailures ?? 'none')}
              tone={m.failed > 0 ? 'danger' : 'success'} />
            <MetricRow label={t.overviewPage?.kpiWeight ?? 'Delivered weight'} value={`${m.weight.toFixed(1)} kg`} sub={`${m.done} ${t.overviewPage?.kpiClosed ?? 'closed'}`} tone="info" />
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {/* Capacity bar */}
            <div className="flex flex-col gap-1">
              <div className="flex items-center justify-between">
                <span className="text-2xs font-medium text-[var(--text-muted)]">{t.overviewPage?.capacityLabel ?? 'Driver load'}</span>
                <span className={cn('text-2xs font-semibold tabular-nums', overCapacity ? 'text-[var(--danger)]' : 'text-[var(--text-secondary)]')}>
                  {m.routesUsing}{driverSlots > 0 ? ` / ${driverSlots}` : ''}
                </span>
              </div>
              <div className="h-1.5 rounded-full bg-[var(--hover-bg)] overflow-hidden">
                <div className="h-full rounded-full transition-all"
                  style={{ width: `${overCapacity ? 100 : capacityPct}%`, background: overCapacity ? 'var(--danger)' : capacityPct >= 80 ? 'var(--warning)' : 'color-mix(in srgb, var(--success) 55%, transparent)' }} />
              </div>
              {overCapacity && (
                <p className="text-3xs font-semibold text-[var(--danger)] flex items-center gap-1">
                  <IconAlertTriangle size={10} /> {t.overviewPage?.overCapacity ?? 'Over capacity — add more drivers'}
                </p>
              )}
            </div>
            <div className="flex flex-col">
              <MetricRow label={t.overviewPage?.kpiPlanned ?? 'Planned deliveries'} value={m.total} sub={isToday ? `${m.completionRate}% ${t.overviewPage?.doneSuffix ?? 'done'}` : `${routes.length} ${t.overviewPage?.routesSuffix ?? 'routes'}`} tone="default" />
              <MetricRow label={t.overviewPage?.kpiUnassigned ?? 'Unassigned'} value={m.unassigned} sub={m.unassigned > 0 ? (t.overviewPage?.toDispatch ?? 'to dispatch') : (t.overviewPage?.allAssigned ?? 'all assigned')}
                tone={m.unassigned > 0 ? 'warning' : 'success'} />
              <MetricRow label={t.overviewPage?.kpiValue ?? 'Parcel value'} value={formatMoney(m.codAll, currency)} sub={t.overviewPage?.kpiValueSub ?? 'to collect'} tone="info" />
              <MetricRow label={t.overviewPage?.kpiWeightPlanned ?? 'Planned weight'} value={`${m.weight.toFixed(1)} kg`} sub={t.overviewPage?.kpiLoad ?? 'est. load'} tone="default" />
            </div>
          </div>
        )}
      </div>

      {/* Lists */}
      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-4">
        {routes.length > 0 && (
          <div>
            <p className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-2">{t.overviewPage?.routesPlanned ?? 'Planned routes'}</p>
            <div className="flex flex-col">
              {routes.map(r => (
                <button key={r.id} onClick={() => navigate(`/routes/${r.id}`)}
                  className="flex items-center gap-2 p-2 rounded-md border-b border-[var(--border)] last:border-b-0 hover:bg-[var(--hover-bg)] text-left w-full transition-colors">
                  <DriverAvatarById driverId={r.driverId} name={r.driverName ?? undefined} size={24} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5 justify-between mb-0.5">
                      <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{r.name}</p>
                      <StatusBadge status={r.status} size="sm" />
                    </div>
                    <p className="text-2xs text-[var(--text-muted)] truncate">{r.driverName ?? t.overviewPage?.unassigned ?? 'Unassigned'}</p>
                    <div className="flex items-center gap-2 mt-0.5 text-2xs text-[var(--text-soft)] font-medium">
                      <span>{r.stops?.length ?? 0} {t.overviewPage?.stops ?? 'stops'}</span>
                      {r.totalDistanceMeters !== undefined && r.totalDistanceMeters > 0 && (
                        <><span>·</span><span>{(r.totalDistanceMeters / 1000).toFixed(1)} km</span></>
                      )}
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}
        <div>
          <p className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-2">{t.overviewPage?.deliveries ?? 'Deliveries'}</p>
          {deliveries.length === 0 ? (
            <div className="flex flex-col items-center py-8 gap-2 opacity-40">
              <IconPackage size={20} stroke={1.5} className="text-[var(--text-muted)]" />
              <span className="text-xs font-medium text-[var(--text-muted)]">{t.overviewPage?.noDeliveries ?? 'No delivery'}</span>
            </div>
          ) : (
            <div className="flex flex-col">
              {deliveries.map(d => (
                <button key={d.deliveryId} onClick={() => navigate(`/deliveries/${d.deliveryId}`)}
                  className="flex flex-col p-2 rounded-md border-b border-[var(--border)] last:border-b-0 hover:bg-[var(--hover-bg)] text-left w-full gap-1 transition-colors">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 flex items-center gap-2">
                      {d.driverName && <DriverAvatarById driverId={d.driverId} name={d.driverName} size={20} />}
                      <div className="min-w-0">
                        <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{d.clientName ?? d.orderRef ?? d.deliveryId.slice(0, 8)}</p>
                        {d.orderRef && <p className="text-2xs font-mono text-[var(--text-muted)]">{d.orderRef}</p>}
                      </div>
                    </div>
                    <StatusBadge status={d.status} size="sm" />
                  </div>
                  <p className="text-2xs text-[var(--text-secondary)] truncate">
                    {d.dropoffCity ?? '—'}{d.driverName ? ` · ${d.driverName}` : ''}
                  </p>
                  <div className="flex items-center justify-between mt-1 pt-1 border-t border-[var(--border)] border-dashed text-2xs text-[var(--text-soft)] font-medium">
                    <span>{d.totalWeightKg ? `${d.totalWeightKg.toFixed(1)} kg` : '— kg'}</span>
                    <span>{d.totalAmount ? formatMoney(d.totalAmount, d.currency ?? 'TND') : '—'}</span>
                  </div>
                  {d.status === 'FAILED' && d.failReason && (
                    <p className="text-2xs font-medium mt-1 px-1.5 py-0.5 rounded bg-[var(--danger-bg)] text-[var(--danger)]">
                      {t.overviewPage?.reason ?? 'Reason'}: {d.failReason}
                    </p>
                  )}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function ModeChip({ mode, live, t }: { mode: 'planning' | 'outcomes'; live: boolean; t: ReturnType<typeof useT> }) {
  if (mode === 'outcomes') {
    return (
      <span className="inline-flex items-center gap-1 text-2xs font-bold px-2 py-1 rounded-full shrink-0"
        style={{ background: 'var(--surface-sunken)', color: 'var(--text-secondary)', border: '1px solid var(--border)' }}>
        <IconCircleCheck size={12} /> {t.overviewPage?.modeOutcomes ?? 'Summary'}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 text-2xs font-bold px-2 py-1 rounded-full shrink-0 bg-[var(--brand-soft)] text-[var(--brand)] border border-[var(--border)]">
      <IconClockHour4 size={12} /> {live ? (t.overviewPage?.modeLive ?? 'Live') : (t.overviewPage?.modePlanning ?? 'Planning')}
    </span>
  );
}
