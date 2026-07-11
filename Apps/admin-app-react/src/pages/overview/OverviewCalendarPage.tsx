import { useMemo, useState } from 'react';
import {
  startOfMonth, endOfMonth, startOfWeek, endOfWeek, addMonths, addWeeks, format, type Locale,
} from 'date-fns';
import { fr, enUS, arEG } from 'date-fns/locale';
import { useQuery } from '@tanstack/react-query';
import { cn } from '@/lib/utils';
import { api } from '@/lib/api';
import { useRoutes, type RouteItem } from '@/hooks/useRoutes';
import { IconChevronLeft, IconChevronRight, IconCalendar, IconLayoutGrid, IconTable } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { useLocaleStore } from '@/lib/i18n';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { CalDelivery, effectiveDate, isoDay } from './shared';
import { MonthView } from './MonthView';
import { WeekView } from './WeekView';

/**
 * Operational planning overview for the dispatcher: a month radar where each day shows planned load,
 * **unassigned backlog**, and **driver-capacity** at a glance, with an adaptive Planning/Outcomes side
 * panel on the selected day. (Intentionally one view — "now" lives in the Dispatch Desk, "actuals" in
 * Performance; this is the time/capacity planning bridge.)
 */
const DFNS_LOCALE: Record<string, Locale> = { fr, en: enUS, ar: arEG };
const dateLocale = (locale: string) => DFNS_LOCALE[locale] ?? fr;

export default function OverviewCalendarPage() {
  const t = useT();
  const { locale } = useLocaleStore();
  const [cursor, setCursor] = useState(() => new Date());
  const [selected, setSelected] = useState<string>(() => isoDay(new Date()));
  const [view, setView] = useState<'month' | 'week'>('month');

  const [filterZone, setFilterZone] = useState('all');
  const [filterDriver, setFilterDriver] = useState('all');
  const [filterStatus, setFilterStatus] = useState('all');

  const rangeFrom = isoDay(startOfWeek(startOfMonth(cursor), { weekStartsOn: 1 }));
  const rangeTo = isoDay(endOfWeek(endOfMonth(cursor), { weekStartsOn: 1 }));

  const { data: routes = [] } = useRoutes({ from: rangeFrom, to: rangeTo });
  const { data: deliveries = [], isFetching: calLoading } = useQuery<CalDelivery[]>({
    queryKey: ['calendar-deliveries', rangeFrom, rangeTo],
    queryFn: async () => {
      const res = await api.get<CalDelivery[]>('/api/admin/deliveries/calendar', { params: { from: rangeFrom, to: rangeTo } });
      return Array.isArray(res.data) ? res.data : [];
    },
  });

  // Active-driver capacity + list for the per-day load bar, planning panel, and week board.
  const { data: driversData } = useQuery<{ count: number; drivers: { id: string; name: string; accountStatus?: string }[] }>({
    queryKey: ['overview-driver-slots'],
    queryFn: async () => {
      const res = await api.get('/api/admin/fleet/drivers');
      const list = Array.isArray(res.data) ? res.data : [];
      const drivers = list.filter((d: { accountStatus?: string }) => !d.accountStatus || d.accountStatus === 'ACTIVE');
      return { count: drivers.length, drivers: drivers.map((d: { id: string; name: string }) => ({ id: d.id, name: d.name })) };
    },
    staleTime: 60_000,
  });
  const driverSlots = driversData?.count ?? 0;
  const activeDrivers = driversData?.drivers ?? [];

  // Filter options derived from current data.
  const availableZones = useMemo(() => Array.from(new Set(deliveries.map(d => d.zoneName).filter(Boolean) as string[])).sort(), [deliveries]);
  const availableDrivers = useMemo(() => {
    const s = new Set<string>();
    deliveries.forEach(d => d.driverName && s.add(d.driverName));
    routes.forEach(r => r.driverName && s.add(r.driverName));
    return Array.from(s).sort();
  }, [deliveries, routes]);
  const availableStatuses = useMemo(() => {
    const s = new Set<string>();
    deliveries.forEach(d => d.status && s.add(d.status));
    routes.forEach(r => r.status && s.add(r.status));
    return Array.from(s).sort();
  }, [deliveries, routes]);

  const filterAttributes = useMemo(() => [
    { key: 'zone', label: t.deliveriesPage?.zoneHeader || 'Zone', options: availableZones.map(z => ({ value: z, label: z })) },
    { key: 'driver', label: t.deliveriesPage?.driverHeader || 'Driver', options: availableDrivers.map(d => ({ value: d, label: d })) },
    { key: 'status', label: t.deliveriesPage?.statusHeader || 'Status', options: availableStatuses.map(s => ({ value: s, label: t.statusLabels[s] || s })) },
  ], [availableZones, availableDrivers, availableStatuses, t]);

  const activeFiltersState = useMemo(() => ({
    ...(filterZone !== 'all' && { zone: filterZone }),
    ...(filterDriver !== 'all' && { driver: filterDriver }),
    ...(filterStatus !== 'all' && { status: filterStatus }),
  }), [filterZone, filterDriver, filterStatus]);

  const handleFilterChange = (key: string, value: string | null) => {
    if (key === 'zone') setFilterZone(value ?? 'all');
    if (key === 'driver') setFilterDriver(value ?? 'all');
    if (key === 'status') setFilterStatus(value ?? 'all');
  };

  // Filtered + bucketed-by-day.
  const filteredDeliveries = useMemo(() => deliveries.filter(d =>
    (filterZone === 'all' || d.zoneName === filterZone) &&
    (filterDriver === 'all' || d.driverName === filterDriver) &&
    (filterStatus === 'all' || d.status === filterStatus)), [deliveries, filterZone, filterDriver, filterStatus]);
  const filteredRoutes = useMemo(() => routes.filter(r =>
    (filterDriver === 'all' || r.driverName === filterDriver) &&
    (filterStatus === 'all' || r.status === filterStatus)), [routes, filterDriver, filterStatus]);

  const deliveriesByDay = useMemo(() => {
    const m = new Map<string, CalDelivery[]>();
    filteredDeliveries.forEach(d => {
      const ed = effectiveDate(d); if (!ed) return;
      const key = ed.slice(0, 10);
      (m.get(key) ?? m.set(key, []).get(key)!).push(d);
    });
    return m;
  }, [filteredDeliveries]);
  const routesByDay = useMemo(() => {
    const m = new Map<string, RouteItem[]>();
    filteredRoutes.forEach(r => {
      if (!r.date) return;
      const key = r.date.slice(0, 10);
      (m.get(key) ?? m.set(key, []).get(key)!).push(r);
    });
    return m;
  }, [filteredRoutes]);

  const goToday = () => { const now = new Date(); setCursor(now); setSelected(isoDay(now)); };
  const shiftPrev = () => setCursor(c => (view === 'week' ? addWeeks(c, -1) : addMonths(c, -1)));
  const shiftNext = () => setCursor(c => (view === 'week' ? addWeeks(c, 1) : addMonths(c, 1)));

  return (
    <div className="h-auto lg:h-[calc(100dvh-56px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Header */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-3 flex items-center justify-between gap-4 max-w-[1800px] mx-auto">
          <div className="flex items-center gap-2 min-w-0">
            <IconCalendar size={16} className="text-[var(--brand)] shrink-0" />
            <h1 className="text-base font-bold text-[var(--text-primary)] capitalize truncate">{format(cursor, 'MMMM yyyy', { locale: dateLocale(locale) })}</h1>
            {calLoading && (
              <span className="ms-1 inline-block h-3.5 w-3.5 shrink-0 rounded-full border-2 border-[var(--border)] border-t-[var(--brand)] animate-spin" role="status" aria-label="…" />
            )}
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            <button
              type="button"
              onClick={() => setView('month')}
              className={cn('px-2.5 py-1 text-2xs font-bold rounded-md border transition-colors flex items-center gap-1 h-7 cursor-pointer', view === 'month' ? 'bg-[var(--surface)] text-[var(--text-primary)] border-[var(--border)]' : 'text-[var(--text-muted)] border-transparent hover:bg-[var(--hover-bg)]')}
            >
              <IconLayoutGrid size={12} /> {t.overviewPage?.monthView ?? 'Month'}
            </button>
            <button
              type="button"
              onClick={() => setView('week')}
              className={cn('px-2.5 py-1 text-2xs font-bold rounded-md border transition-colors flex items-center gap-1 h-7 cursor-pointer', view === 'week' ? 'bg-[var(--surface)] text-[var(--text-primary)] border-[var(--border)]' : 'text-[var(--text-muted)] border-transparent hover:bg-[var(--hover-bg)]')}
            >
              <IconTable size={12} /> {t.overviewPage?.weekView ?? 'Week'}
            </button>
          </div>
        </div>
      </div>

      <PageFilterBar
        attributes={filterAttributes}
        activeFilters={activeFiltersState}
        onFilterChange={handleFilterChange}
        extraActions={
          <div className="ml-auto flex items-center gap-2">
            <button onClick={shiftPrev} aria-label={t.overviewPage?.prev ?? 'Previous'} className="w-8 h-8 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"><IconChevronLeft size={16} /></button>
            <button onClick={goToday} className="h-8 px-3 rounded-md border border-[var(--border)] text-sm font-bold text-[var(--text-secondary)] hover:bg-[var(--hover-bg)] transition-colors">{t.overviewPage?.today ?? 'Today'}</button>
            <button onClick={shiftNext} aria-label={t.overviewPage?.next ?? 'Next'} className="w-8 h-8 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"><IconChevronRight size={16} /></button>
          </div>
        }
      />

      {view === 'month' ? (
        <MonthView cursor={cursor} selected={selected} setSelected={setSelected}
          deliveriesByDay={deliveriesByDay} routesByDay={routesByDay} driverSlots={driverSlots} t={t} />
      ) : (
        <WeekView cursor={cursor} selected={selected} setSelected={setSelected}
          deliveriesByDay={deliveriesByDay} routesByDay={routesByDay} drivers={activeDrivers} driverSlots={driverSlots} t={t} />
      )}
    </div>
  );
}
