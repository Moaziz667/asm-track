import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { parseISO, isBefore, isSameDay } from 'date-fns';
import { fr } from 'date-fns/locale';
import { format } from 'date-fns';
import { IconPackage, IconAlertTriangle, IconClockHour4, IconCircleCheck } from '@tabler/icons-react';
import { KPICard } from '@/components/ui/kpi-card';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { formatMoney } from '@/lib/utils';
import type { useT } from '@/lib/LocaleContext';
import type { RouteItem } from '@/hooks/useRoutes';
import { CalDelivery, STATUS_COLOR, TERMINAL, startOfToday } from './shared';

interface Props {
  selected: string;
  deliveries: CalDelivery[];
  routes: RouteItem[];
  driverSlots: number; // available driver capacity for planning
  t: ReturnType<typeof useT>;
}

/**
 * Adaptive day panel: a PAST day shows Outcomes (what happened — completion, COD, failures); TODAY or a
 * FUTURE day shows Planning (what's coming — planned load vs capacity, unassigned backlog). Today is a
 * hybrid: planning layout, but with the live completion already filled in.
 */
export function DayPanel({ selected, deliveries, routes, driverSlots, t }: Props) {
  const navigate = useNavigate();
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
            {format(day, 'EEEE d MMMM', { locale: fr })}
          </p>
          <p className="text-xs text-[var(--text-muted)]">{deliveries.length} livraison(s) · {routes.length} tournée(s)</p>
        </div>
        <ModeChip mode={mode} live={isToday} t={t} />
      </div>

      {/* KPI band — adapts to mode */}
      <div className="px-3 py-3 border-b border-[var(--border)] shrink-0 bg-[var(--surface-sunken)]">
        {mode === 'outcomes' ? (
          <div className="grid grid-cols-2 gap-2">
            <KPICard label={t.overviewPage?.kpiCompletion ?? 'Taux de réussite'} value={`${m.completionRate}%`} sub={`${m.delivered} / ${m.total}`}
              tone={m.completionRate >= 80 ? 'success' : m.completionRate >= 50 ? 'warning' : m.total > 0 ? 'danger' : 'default'}
              className="pl-4 pr-2.5 py-3 h-[76px]" />
            <KPICard label={t.overviewPage?.kpiCollected ?? 'Encaissé (COD)'} value={formatMoney(m.codCollected, currency)} sub={`/ ${formatMoney(m.codAll, currency)}`}
              tone="success" className="pl-4 pr-2.5 py-3 h-[76px]" />
            <KPICard label={t.overviewPage?.kpiFailures ?? 'Échecs'} value={m.failed} sub={m.failed > 0 ? (t.overviewPage?.kpiFailuresSub ?? 'à analyser') : (t.overviewPage?.kpiNoFailures ?? 'aucun')}
              tone={m.failed > 0 ? 'danger' : 'success'} className="pl-4 pr-2.5 py-3 h-[76px]" />
            <KPICard label={t.overviewPage?.kpiWeight ?? 'Poids livré'} value={`${m.weight.toFixed(1)} kg`} sub={`${m.done} ${t.overviewPage?.kpiClosed ?? 'clôturées'}`}
              tone="info" className="pl-4 pr-2.5 py-3 h-[76px]" />
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            {/* Capacity bar */}
            <div className="rounded-[var(--radius-xl)] border border-[var(--border)] bg-[var(--surface)] px-3.5 py-2.5">
              <div className="flex items-center justify-between mb-1.5">
                <span className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)]">{t.overviewPage?.capacityLabel ?? 'Charge chauffeurs'}</span>
                <span className="text-2xs font-bold tabular-nums" style={{ color: overCapacity ? 'var(--danger)' : 'var(--text-secondary)' }}>
                  {m.routesUsing}{driverSlots > 0 ? ` / ${driverSlots}` : ''}
                </span>
              </div>
              <div className="h-[6px] rounded-full bg-[var(--surface-sunken)] overflow-hidden">
                <div className="h-full rounded-full transition-all"
                  style={{ width: `${overCapacity ? 100 : capacityPct}%`, background: overCapacity ? 'var(--danger)' : capacityPct >= 80 ? 'var(--warning)' : 'var(--success)' }} />
              </div>
              {overCapacity && (
                <p className="text-3xs font-semibold text-[var(--danger)] mt-1 flex items-center gap-1">
                  <IconAlertTriangle size={11} /> {t.overviewPage?.overCapacity ?? 'Surcharge — ajouter de la capacité'}
                </p>
              )}
            </div>
            <div className="grid grid-cols-2 gap-2">
              <KPICard label={t.overviewPage?.kpiPlanned ?? 'Livr. planifiées'} value={m.total} sub={isToday ? `${m.completionRate}% ${t.overviewPage?.doneSuffix ?? 'fait'}` : `${routes.length} ${t.overviewPage?.routesSuffix ?? 'tournées'}`}
                tone="default" className="pl-4 pr-2.5 py-3 h-[76px]" />
              <KPICard label={t.overviewPage?.kpiUnassigned ?? 'Non assignées'} value={m.unassigned} sub={m.unassigned > 0 ? (t.overviewPage?.toDispatch ?? 'à dispatcher') : (t.overviewPage?.allAssigned ?? 'tout assigné')}
                tone={m.unassigned > 0 ? 'warning' : 'success'} className="pl-4 pr-2.5 py-3 h-[76px]" />
              <KPICard label={t.overviewPage?.kpiValue ?? 'Valeur colis'} value={formatMoney(m.codAll, currency)} sub={t.overviewPage?.kpiValueSub ?? 'à encaisser'}
                tone="info" className="pl-4 pr-2.5 py-3 h-[76px]" />
              <KPICard label={t.overviewPage?.kpiWeightPlanned ?? 'Poids prévu'} value={`${m.weight.toFixed(1)} kg`} sub={t.overviewPage?.kpiLoad ?? 'charge estimée'}
                tone="default" className="pl-4 pr-2.5 py-3 h-[76px]" />
            </div>
          </div>
        )}
      </div>

      {/* Lists */}
      <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-4">
        {routes.length > 0 && (
          <div>
            <p className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-2">{t.overviewPage?.routesPlanned ?? 'Tournées planifiées'}</p>
            <div className="flex flex-col gap-1.5">
              {routes.map(r => (
                <button key={r.id} onClick={() => navigate(`/routes/${r.id}`)}
                  className="flex items-center gap-2.5 p-2.5 rounded-lg border border-[var(--border)] hover:bg-[var(--hover-bg)] text-left w-full transition-colors">
                  <DriverAvatarById driverId={r.driverId} name={r.driverName ?? undefined} size={28} />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-1.5 justify-between mb-0.5">
                      <p className="text-sm font-bold text-[var(--text-primary)] truncate">{r.name}</p>
                      <StatusBadge status={r.status} size="sm" />
                    </div>
                    <p className="text-xs text-[var(--text-muted)] truncate">{r.driverName ?? t.overviewPage?.unassigned ?? 'Non assigné'}</p>
                    <div className="flex items-center gap-2 mt-1 text-2xs text-[var(--text-muted)] font-medium">
                      <span>{r.stops?.length ?? 0} {t.overviewPage?.stops ?? 'arrêts'}</span>
                      {r.totalDistanceMeters !== undefined && r.totalDistanceMeters > 0 && (
                        <><span>•</span><span>{(r.totalDistanceMeters / 1000).toFixed(1)} km</span></>
                      )}
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </div>
        )}
        <div>
          <p className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-2">{t.overviewPage?.deliveries ?? 'Livraisons'}</p>
          {deliveries.length === 0 ? (
            <div className="flex flex-col items-center py-8 gap-2 opacity-40">
              <IconPackage size={22} /><span className="text-xs font-semibold">{t.overviewPage?.noDeliveries ?? 'Aucune livraison'}</span>
            </div>
          ) : (
            <div className="flex flex-col gap-1.5">
              {deliveries.map(d => (
                <button key={d.deliveryId} onClick={() => navigate(`/deliveries/${d.deliveryId}`)}
                  className="flex flex-col p-2.5 rounded-lg border border-[var(--border)] hover:bg-[var(--hover-bg)] text-left w-full gap-1 transition-colors">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 flex items-center gap-2">
                      {d.driverName && <DriverAvatarById driverId={d.driverId} name={d.driverName} size={20} />}
                      <div className="min-w-0">
                        <p className="text-sm font-bold text-[var(--text-primary)] truncate">{d.clientName ?? d.orderRef ?? d.deliveryId.slice(0, 8)}</p>
                        {d.orderRef && <p className="text-2xs font-mono text-[var(--text-muted)]">{d.orderRef}</p>}
                      </div>
                    </div>
                    <StatusBadge status={d.status} size="sm" />
                  </div>
                  <p className="text-xs text-[var(--text-secondary)] truncate">
                    {d.dropoffCity ?? '—'}{d.driverName ? ` · ${d.driverName}` : ''}
                  </p>
                  <div className="flex items-center justify-between mt-1 pt-1 border-t border-[var(--border)] border-dashed text-2xs text-[var(--text-muted)] font-semibold">
                    <span>{d.totalWeightKg ? `${d.totalWeightKg.toFixed(1)} kg` : '— kg'}</span>
                    <span>{d.totalAmount ? formatMoney(d.totalAmount, d.currency ?? 'TND') : '—'}</span>
                  </div>
                  {d.status === 'FAILED' && d.failReason && (
                    <p className="text-2xs font-medium mt-1 px-1.5 py-0.5 rounded" style={{ color: 'var(--danger)', background: 'var(--danger-bg)' }}>
                      {t.overviewPage?.reason ?? 'Motif'}: {d.failReason}
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
        <IconCircleCheck size={12} /> {t.overviewPage?.modeOutcomes ?? 'Bilan'}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 text-2xs font-bold px-2 py-1 rounded-full shrink-0"
      style={{ background: 'var(--brand-bg)', color: 'var(--brand)', border: '1px solid var(--border)' }}>
      <IconClockHour4 size={12} /> {live ? (t.overviewPage?.modeLive ?? 'En direct') : (t.overviewPage?.modePlanning ?? 'Planification')}
    </span>
  );
}
