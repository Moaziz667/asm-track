import { useMemo, useState } from 'react';
import {
  startOfMonth, endOfMonth, startOfWeek, endOfWeek, addMonths, addDays, format,
} from 'date-fns';
import { fr } from 'date-fns/locale';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useRoutes, type RouteItem } from '@/hooks/useRoutes';
import { IconChevronLeft, IconChevronRight, IconCalendar } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
import { PageFilterBar } from '@/components/layout/PageFilterBar';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import { CalDelivery, OverviewView, effectiveDate, isoDay } from './overview/shared';
import { MonthView } from './overview/MonthView';
import { DayGridView } from './overview/DayGridView';
import { TimelineView } from './overview/TimelineView';

const TIMELINE_MONTHS = 6;

export default function OverviewCalendarPage() {
  const t = useT();
  const [view, setView] = useState<OverviewView>('month');
  const [cursor, setCursor] = useState(() => new Date());
  const [selected, setSelected] = useState<string>(() => isoDay(new Date()));

  const [filterZone, setFilterZone] = useState('all');
  const [filterDriver, setFilterDriver] = useState('all');
  const [filterStatus, setFilterStatus] = useState('all');

  // Fetch range depends on the active view: month grid, a single day, or a 6-month window.
  const { rangeFrom, rangeTo } = useMemo(() => {
    if (view === 'day') return { rangeFrom: selected, rangeTo: selected };
    if (view === 'timeline') {
      return { rangeFrom: isoDay(startOfMonth(cursor)), rangeTo: isoDay(endOfMonth(addMonths(cursor, TIMELINE_MONTHS - 1))) };
    }
    return { rangeFrom: isoDay(startOfWeek(startOfMonth(cursor), { weekStartsOn: 1 })), rangeTo: isoDay(endOfWeek(endOfMonth(cursor), { weekStartsOn: 1 })) };
  }, [view, cursor, selected]);

  const { data: routes = [] } = useRoutes({ from: rangeFrom, to: rangeTo });
  const { data: deliveries = [] } = useQuery<CalDelivery[]>({
    queryKey: ['calendar-deliveries', rangeFrom, rangeTo],
    queryFn: async () => {
      const res = await api.get<CalDelivery[]>('/api/admin/deliveries/calendar', { params: { from: rangeFrom, to: rangeTo } });
      return Array.isArray(res.data) ? res.data : [];
    },
  });

  // Active-driver capacity for the Planning panel.
  const { data: driverSlots = 0 } = useQuery<number>({
    queryKey: ['overview-driver-slots'],
    queryFn: async () => {
      const res = await api.get('/api/admin/fleet/drivers');
      const list = Array.isArray(res.data) ? res.data : [];
      return list.filter((d: { accountStatus?: string }) => !d.accountStatus || d.accountStatus === 'ACTIVE').length;
    },
    staleTime: 60_000,
  });

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
    { key: 'driver', label: t.deliveriesPage?.driverHeader || 'Chauffeur', options: availableDrivers.map(d => ({ value: d, label: d })) },
    { key: 'status', label: t.deliveriesPage?.statusHeader || 'Statut', options: availableStatuses.map(s => ({ value: s, label: t.statusLabels[s] || s })) },
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

  const dayDeliveries = deliveriesByDay.get(selected) ?? [];

  // Header title + nav step adapt to view.
  const headerTitle = view === 'day'
    ? format(new Date(selected), 'EEEE d MMMM yyyy', { locale: fr })
    : view === 'timeline'
      ? `${format(cursor, 'MMM', { locale: fr })} – ${format(addMonths(cursor, TIMELINE_MONTHS - 1), 'MMM yyyy', { locale: fr })}`
      : format(cursor, 'MMMM yyyy', { locale: fr });

  const step = (dir: 1 | -1) => {
    if (view === 'day') { const d = addDays(new Date(selected), dir); setSelected(isoDay(d)); setCursor(d); }
    else setCursor(c => addMonths(c, dir));
  };
  const goToday = () => { const now = new Date(); setCursor(now); setSelected(isoDay(now)); };

  return (
    <div className="h-auto lg:h-[calc(100dvh-56px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Header */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-3 flex items-center justify-between gap-4 max-w-[1800px] mx-auto">
          <div className="flex items-center gap-3 min-w-0">
            <IconCalendar size={18} className="text-[var(--brand)] shrink-0" />
            <h1 className="text-lg font-bold text-[var(--text-primary)] capitalize truncate">{headerTitle}</h1>
          </div>
          <SegmentedControl<OverviewView>
            value={view}
            onChange={setView}
            ariaLabel={t.overviewPage?.viewLabel ?? 'Vue'}
            options={[
              { value: 'month', label: t.overviewPage?.viewMonth ?? 'Mois' },
              { value: 'day', label: t.overviewPage?.viewDay ?? 'Jour' },
              { value: 'timeline', label: t.overviewPage?.viewTimeline ?? 'Frise' },
            ]}
          />
        </div>
      </div>

      <PageFilterBar
        attributes={filterAttributes}
        activeFilters={activeFiltersState}
        onFilterChange={handleFilterChange}
        extraActions={
          <div className="ml-auto flex items-center gap-2">
            <button onClick={() => step(-1)} className="w-8 h-8 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"><IconChevronLeft size={16} /></button>
            <button onClick={goToday} className="h-8 px-3 rounded-md border border-[var(--border)] text-sm font-bold text-[var(--text-secondary)] hover:bg-[var(--hover-bg)] transition-colors">{t.overviewPage?.today ?? "Aujourd'hui"}</button>
            <button onClick={() => step(1)} className="w-8 h-8 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"><IconChevronRight size={16} /></button>
          </div>
        }
      />

      {view === 'month' && (
        <MonthView cursor={cursor} selected={selected} setSelected={setSelected}
          deliveriesByDay={deliveriesByDay} routesByDay={routesByDay} driverSlots={driverSlots} t={t} />
      )}
      {view === 'day' && <DayGridView selected={selected} deliveries={dayDeliveries} t={t} />}
      {view === 'timeline' && (
        <TimelineView cursor={cursor} months={TIMELINE_MONTHS} deliveriesByDay={deliveriesByDay} routesByDay={routesByDay}
          selected={selected} onPickDay={(iso) => { setSelected(iso); setCursor(new Date(iso)); setView('month'); }} t={t} />
      )}
    </div>
  );
}
