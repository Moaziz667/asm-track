import { useMemo } from 'react';
import { startOfWeek, addDays, format, isSameDay } from 'date-fns';
import { IconRoute, IconPackage } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import type { useT } from '@/lib/i18n/LocaleContext';
import type { RouteItem } from '@/hooks/useRoutes';
import { CalDelivery, isoDay } from './shared';
import { DayPanel } from './DayPanel';

interface Props {
  cursor: Date;
  selected: string;
  setSelected: (d: string) => void;
  deliveriesByDay: Map<string, CalDelivery[]>;
  routesByDay: Map<string, RouteItem[]>;
  drivers: { id: string; name: string }[];
  driverSlots: number;
  t: ReturnType<typeof useT>;
}

/** Driver-centric weekly load board: rows = drivers, columns = days.
 *  The classic enterprise resource-planning view for dispatchers — who is
 *  assigned, on which days, with how much load, at a glance. */
export function WeekView({ cursor, selected, setSelected, deliveriesByDay, routesByDay, drivers, driverSlots, t }: Props) {
  const weekStart = useMemo(() => startOfWeek(cursor, { weekStartsOn: 1 }), [cursor]);
  const days = useMemo(() => Array.from({ length: 7 }, (_, i) => addDays(weekStart, i)), [weekStart]);
  const today = new Date();

  // Build rows for every active driver, sorted by name. Drivers without any
  // assignment this week still appear so dispatchers see free capacity.
  const rows = useMemo(() => {
    // Collect ids + names across ALL days (a driver may only appear later in the week, and drivers
    // not in the active-fleet list — e.g. suspended — still need their name, not a UUID prefix).
    const ids = new Set<string>();
    const nameById = new Map<string, string>();
    drivers.forEach(d => { ids.add(d.id); nameById.set(d.id, d.name); });
    routesByDay.forEach(rts => rts.forEach(r => {
      if (!r.driverId) return;
      ids.add(r.driverId);
      if (r.driverName && !nameById.has(r.driverId)) nameById.set(r.driverId, r.driverName);
    }));
    deliveriesByDay.forEach(dels => dels.forEach(d => {
      if (!d.driverId) return;
      ids.add(d.driverId);
      if (d.driverName && !nameById.has(d.driverId)) nameById.set(d.driverId, d.driverName);
    }));

    return Array.from(ids)
      .map(id => ({ id, name: nameById.get(id) ?? id.slice(0, 8) }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [drivers, routesByDay, deliveriesByDay]);

  const selectedDeliveries = deliveriesByDay.get(selected) ?? [];
  const selectedRoutes = routesByDay.get(selected) ?? [];

  return (
    <div className="flex flex-col lg:flex-row flex-1 min-h-0 max-w-[1800px] mx-auto w-full overflow-visible lg:overflow-hidden">
      {/* Board */}
      <div className="flex-1 flex flex-col p-4 min-w-0">
        <div className="border border-[var(--border)] rounded-lg bg-[var(--app-bg)] overflow-hidden flex flex-col">
          {/* Header row */}
          <div className="grid grid-cols-8 border-b border-[var(--border)] bg-[var(--surface-sunken)]">
            <div className="px-3 py-2 border-r border-[var(--border)] text-2xs font-medium text-[var(--text-muted)] flex items-center">
              {t.overviewPage?.driverHeader ?? 'Driver'}
            </div>
            {days.map(day => {
              const key = isoDay(day);
              const isToday = isSameDay(day, today);
              const isSelected = key === selected;
              return (
                <button
                  key={key}
                  type="button"
                  onClick={() => setSelected(key)}
                  className={cn(
                    'px-2 py-2 text-center border-r border-[var(--border)] last:border-r-0 transition-colors hover:bg-[var(--hover-bg)]',
                    isSelected && 'bg-[var(--brand-soft)]',
                    isToday && !isSelected && 'bg-[var(--hover-bg)]'
                  )}
                >
                  <span className={cn('text-2xs font-medium block', isToday ? 'text-[var(--brand)]' : 'text-[var(--text-muted)]')}>
                    {format(day, 'EEE')}
                  </span>
                  <span className={cn('text-sm font-bold tabular-nums block', isToday ? 'text-[var(--brand)]' : 'text-[var(--text-primary)]')}>
                    {format(day, 'd')}
                  </span>
                </button>
              );
            })}
          </div>

          {/* Driver rows */}
          <div className="flex-1 overflow-y-auto">
            {rows.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 gap-2 opacity-40">
                <IconRoute size={20} stroke={1.5} className="text-[var(--text-muted)]" />
                <span className="text-xs font-medium text-[var(--text-muted)]">{t.overviewPage?.noDrivers ?? 'No drivers'}</span>
              </div>
            ) : (
              rows.map(row => (
                <div key={row.id} className="grid grid-cols-8 border-b border-[var(--border)] last:border-b-0 hover:bg-[var(--hover-bg)]/50 transition-colors">
                  <div className="px-3 py-2 border-r border-[var(--border)] flex items-center gap-2 min-w-0">
                    <DriverAvatarById driverId={row.id} name={row.name} size={24} />
                    <span className="text-xs font-semibold text-[var(--text-primary)] truncate">{row.name}</span>
                  </div>
                  {days.map(day => {
                    const key = isoDay(day);
                    const rts = routesByDay.get(key) ?? [];
                    const route = rts.find(r => r.driverId === row.id);
                    const dels = (deliveriesByDay.get(key) ?? []).filter(d => d.driverId === row.id);
                    const isSelected = key === selected;
                    return (
                      <button
                        key={key}
                        type="button"
                        onClick={() => setSelected(key)}
                        className={cn(
                          'px-2 py-2 border-r border-[var(--border)] last:border-r-0 text-left transition-colors min-h-[64px]',
                          isSelected ? 'bg-[var(--brand-soft)]' : 'hover:bg-[var(--hover-bg)]'
                        )}
                      >
                        {route ? (
                          <div className="flex flex-col gap-1 min-w-0">
                            <StatusBadge status={route.status} size="sm" />
                            <span className="text-2xs font-semibold text-[var(--text-primary)] truncate">{route.name}</span>
                            <span className="text-3xs text-[var(--text-muted)] inline-flex items-center gap-1">
                              <IconPackage size={9} stroke={2} /> {dels.length || (route.stops?.length ?? 0)}
                            </span>
                          </div>
                        ) : dels.length > 0 ? (
                          <div className="flex flex-col gap-1 min-w-0">
                            <span className="text-2xs font-semibold text-[var(--warning)]">{dels.length} {t.overviewPage?.delAbbrev ?? 'del.'}</span>
                            <span className="text-3xs text-[var(--text-soft)]">{t.overviewPage?.unassigned ?? 'Unassigned'}</span>
                          </div>
                        ) : (
                          <span className="text-2xs text-[var(--text-faded)]">—</span>
                        )}
                      </button>
                    );
                  })}
                </div>
              ))
            )}
          </div>
        </div>
      </div>

      <DayPanel selected={selected} deliveries={selectedDeliveries} routes={selectedRoutes} driverSlots={driverSlots} t={t} />
    </div>
  );
}
