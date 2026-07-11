import { useMemo } from 'react';
import { startOfMonth, endOfMonth, startOfWeek, endOfWeek, addDays, format, isSameMonth, isSameDay, isBefore } from 'date-fns';
import { IconRoute, IconAlertTriangle } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import type { useT } from '@/lib/i18n/LocaleContext';
import type { RouteItem } from '@/hooks/useRoutes';
import { CalDelivery, STATUS_TONE_MAP, TONE_VAR, isoDay } from './shared';
import { DayPanel } from './DayPanel';

interface Props {
  cursor: Date;
  selected: string;
  setSelected: (d: string) => void;
  deliveriesByDay: Map<string, CalDelivery[]>;
  routesByDay: Map<string, RouteItem[]>;
  driverSlots: number;
  t: ReturnType<typeof useT>;
}

const WEEKDAYS = ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'];

export function MonthView({ cursor, selected, setSelected, deliveriesByDay, routesByDay, driverSlots, t }: Props) {
  const gridStart = useMemo(() => startOfWeek(startOfMonth(cursor), { weekStartsOn: 1 }), [cursor]);
  const gridEnd = useMemo(() => endOfWeek(endOfMonth(cursor), { weekStartsOn: 1 }), [cursor]);
  const days = useMemo(() => {
    const arr: Date[] = [];
    let d = gridStart;
    while (d <= gridEnd) { arr.push(d); d = addDays(d, 1); }
    return arr;
  }, [gridStart, gridEnd]);

  const selectedDeliveries = deliveriesByDay.get(selected) ?? [];
  const selectedRoutes = routesByDay.get(selected) ?? [];

  return (
    <div className="flex flex-col lg:flex-row flex-1 min-h-0 max-w-[1800px] mx-auto w-full overflow-visible lg:overflow-hidden">
      <div className="flex-1 flex flex-col p-4 min-w-0">
        <div className="grid grid-cols-7 gap-px mb-1">
          {WEEKDAYS.map(w => (
            <div key={w} className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] text-center py-1">{w}</div>
          ))}
        </div>
        <div className="grid grid-cols-7 gap-1 flex-1 auto-rows-fr min-h-[320px] lg:min-h-0">
          {days.map(day => {
            const key = isoDay(day);
            const dels = deliveriesByDay.get(key) ?? [];
            const rts = routesByDay.get(key) ?? [];
            const inMonth = isSameMonth(day, cursor);
            const isToday = isSameDay(day, new Date());
            const isPast = isBefore(day, new Date()) && !isToday;
            const isSelected = key === selected;
            const statusCounts = dels.reduce<Record<string, number>>((acc, d) => { acc[d.status] = (acc[d.status] ?? 0) + 1; return acc; }, {});
            // Dispatcher signal: unassigned backlog (actionable) + fleet utilization (distinct drivers
            // committed that day / available drivers) — same definition as the day panel, and it no longer
            // over-counts a driver who runs several routes.
            const unassigned = dels.filter(d => !d.driverId).length;
            const driversUsed = new Set(rts.map(r => r.driverId).filter(Boolean)).size;
            const capPct = driverSlots > 0 ? Math.min(driversUsed / driverSlots, 1) : 0;
            const over = driverSlots > 0 && driversUsed > driverSlots;
            return (
              <button key={key} onClick={() => setSelected(key)}
                className={cn(
                  'flex flex-col items-start p-2 rounded-lg border text-left transition-colors overflow-hidden',
                  isSelected ? 'border-[var(--brand)] bg-[var(--hover-bg)]' : 'border-[var(--border)] hover:bg-[var(--hover-bg)]',
                  !inMonth && 'opacity-40',
                )}
                style={{ background: isSelected ? undefined : 'var(--app-bg)' }}>
                <div className="flex items-center justify-between w-full">
                  <span className={cn('text-sm font-bold', isToday ? 'text-[var(--brand)]' : 'text-[var(--text-primary)]')}>{format(day, 'd')}</span>
                  <div className="flex items-center gap-1">
                    {/* Unassigned backlog — only future/today (a past unassigned is noise) */}
                    {unassigned > 0 && !isPast && (
                      <span className="inline-flex items-center gap-0.5 text-2xs font-bold px-1 rounded"
                        title={`${unassigned} ${t.overviewPage?.kpiUnassigned ?? 'non assignées'}`}
                        style={{ background: 'var(--warning-bg)', color: 'var(--warning)' }}>
                        <IconAlertTriangle size={9} /> {unassigned}
                      </span>
                    )}
                    {rts.length > 0 && (
                      <span className="inline-flex items-center gap-0.5 text-2xs font-bold text-[var(--text-muted)]"><IconRoute size={10} /> {rts.length}</span>
                    )}
                  </div>
                </div>
                {dels.length > 0 && (
                  <>
                    <div className="flex flex-wrap gap-0.5 mt-1.5">
                      {Object.entries(statusCounts).slice(0, 4).map(([s, n]) => {
                        const tone = STATUS_TONE_MAP[s] ?? 'muted';
                        return (
                          <span key={s} className="inline-flex items-center gap-0.5 text-2xs font-bold px-1 rounded bg-[var(--hover-bg)]"
                            style={{ color: TONE_VAR[tone] }}>{n}</span>
                        );
                      })}
                    </div>
                    <span className="mt-auto text-2xs font-semibold text-[var(--text-muted)]">{dels.length} {t.overviewPage?.delAbbrev ?? 'livr.'}</span>
                  </>
                )}
                {/* Capacity load bar (only when relevant — future/today with routes) */}
                {rts.length > 0 && !isPast && driverSlots > 0 && (
                  <div className="w-full h-[3px] rounded-full mt-1 bg-[var(--hover-bg)] overflow-hidden"
                    title={`${driversUsed}/${driverSlots} ${t.overviewPage?.capacityLabel ?? 'capacité'}`}>
                    <div className="h-full rounded-full" style={{ width: `${(over ? 1 : capPct) * 100}%`, background: over ? 'var(--danger)' : capPct >= 0.8 ? 'var(--warning)' : 'var(--success)' }} />
                  </div>
                )}
              </button>
            );
          })}
        </div>
      </div>

      <DayPanel selected={selected} deliveries={selectedDeliveries} routes={selectedRoutes} driverSlots={driverSlots} t={t} />
    </div>
  );
}
