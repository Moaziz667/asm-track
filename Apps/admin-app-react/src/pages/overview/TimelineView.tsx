import { useMemo } from 'react';
import { startOfMonth, endOfMonth, addMonths, addDays, getDate, isSameDay, format } from 'date-fns';
import { fr } from 'date-fns/locale';
import { cn } from '@/lib/utils';
import type { useT } from '@/lib/LocaleContext';
import type { RouteItem } from '@/hooks/useRoutes';
import { CalDelivery, isoDay } from './shared';

interface Props {
  cursor: Date;
  months: number;
  deliveriesByDay: Map<string, CalDelivery[]>;
  routesByDay: Map<string, RouteItem[]>;
  selected: string;
  onPickDay: (iso: string) => void;
  t: ReturnType<typeof useT>;
}

/**
 * Multi-month density timeline: each month is a row, each day a cell whose fill encodes delivery volume
 * (heat) with a route-count marker. The "year at a glance" view — deliveries are single-day events, so a
 * heat strip is honest where multi-day Gantt bars would be fake.
 */
export function TimelineView({ cursor, months, deliveriesByDay, routesByDay, selected, onPickDay, t }: Props) {
  const monthList = useMemo(
    () => Array.from({ length: months }, (_, i) => startOfMonth(addMonths(cursor, i))),
    [cursor, months],
  );

  // Peak daily volume across the window → heat normalization.
  const peak = useMemo(() => {
    let max = 0;
    deliveriesByDay.forEach(v => { if (v.length > max) max = v.length; });
    return Math.max(max, 1);
  }, [deliveriesByDay]);

  const dayCols = Array.from({ length: 31 }, (_, i) => i + 1);

  return (
    <div className="flex-1 min-h-0 max-w-[1800px] mx-auto w-full overflow-auto p-4">
      <div className="min-w-[820px]">
        {/* Day-number header */}
        <div className="flex items-center mb-1 sticky top-0 bg-[var(--app-bg)] z-10">
          <div className="w-24 shrink-0" />
          <div className="flex-1 grid" style={{ gridTemplateColumns: `repeat(31, minmax(0,1fr))` }}>
            {dayCols.map(d => (
              <div key={d} className="text-3xs font-bold text-[var(--text-muted)] text-center tabular-nums">{d}</div>
            ))}
          </div>
        </div>

        {monthList.map(month => {
          const daysInMonth = getDate(endOfMonth(month));
          return (
            <div key={isoDay(month)} className="flex items-center h-9 border-b border-[var(--border)]">
              <div className="w-24 shrink-0 text-xs font-bold text-[var(--text-primary)] capitalize truncate pr-2">
                {format(month, 'MMM yyyy', { locale: fr })}
              </div>
              <div className="flex-1 grid h-7" style={{ gridTemplateColumns: `repeat(31, minmax(0,1fr))` }}>
                {dayCols.map(dnum => {
                  if (dnum > daysInMonth) return <div key={dnum} />;
                  const date = addDays(month, dnum - 1);
                  const key = isoDay(date);
                  const dels = deliveriesByDay.get(key) ?? [];
                  const rts = routesByDay.get(key) ?? [];
                  const intensity = dels.length / peak; // 0..1
                  const isToday = isSameDay(date, new Date());
                  const isSelected = key === selected;
                  return (
                    <button key={dnum} onClick={() => onPickDay(key)}
                      title={`${format(date, 'EEE d MMM', { locale: fr })} — ${dels.length} ${t.overviewPage?.delAbbrev ?? 'livr.'}, ${rts.length} ${t.overviewPage?.routesShort ?? 'tourn.'}`}
                      className={cn('mx-px rounded-[3px] relative transition-all hover:ring-1 hover:ring-[var(--brand)]',
                        isSelected && 'ring-2 ring-[var(--brand)]')}
                      style={{
                        background: dels.length === 0
                          ? 'var(--surface-sunken)'
                          : `color-mix(in srgb, var(--brand) ${15 + intensity * 70}%, transparent)`,
                        outline: isToday ? '1px solid var(--brand)' : undefined,
                      }}>
                      {rts.length > 0 && (
                        <span className="absolute bottom-0.5 right-0.5 w-1 h-1 rounded-full" style={{ background: 'var(--text-primary)' }} />
                      )}
                    </button>
                  );
                })}
              </div>
            </div>
          );
        })}

        {/* Legend */}
        <div className="flex items-center gap-4 mt-4 text-2xs text-[var(--text-muted)]">
          <div className="flex items-center gap-1.5">
            <span>{t.overviewPage?.legendLess ?? 'Moins'}</span>
            {[15, 40, 65, 85].map(p => (
              <span key={p} className="w-3.5 h-3.5 rounded-[3px]" style={{ background: `color-mix(in srgb, var(--brand) ${p}%, transparent)` }} />
            ))}
            <span>{t.overviewPage?.legendMore ?? 'Plus'}</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="w-1 h-1 rounded-full" style={{ background: 'var(--text-primary)' }} />
            <span>{t.overviewPage?.legendRoutes ?? 'Tournées planifiées'}</span>
          </div>
        </div>
      </div>
    </div>
  );
}
