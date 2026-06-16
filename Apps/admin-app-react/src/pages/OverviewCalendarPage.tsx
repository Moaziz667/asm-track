import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  startOfMonth, endOfMonth, startOfWeek, endOfWeek, addDays, addMonths,
  format, isSameMonth, isSameDay, parseISO,
} from 'date-fns';
import { fr } from 'date-fns/locale';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useRoutes, type RouteItem } from '@/hooks/useRoutes';
import { IconChevronLeft, IconChevronRight, IconRoute, IconPackage, IconCalendar } from '@tabler/icons-react';
import { cn, formatMoney } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { KPICard } from '@/components/ui/kpi-card';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { PageFilterBar } from '@/components/layout/PageFilterBar';

// Effective scheduled date for a delivery: rescheduled ∨ scheduled ∨ created.
interface CalDelivery {
  deliveryId: string;
  orderRef?: string;
  clientName?: string;
  dropoffCity?: string;
  status: string;
  routeId?: string;
  routeName?: string;
  driverName?: string;
  scheduledAt?: string;
  rescheduledAt?: string;
  createdAt?: string;
  totalAmount?: number;
  totalWeightKg?: number;
  itemsSummary?: string;
  failureCode?: string;
  failReason?: string;
  zoneName?: string;
  currency?: string;
}

const STATUS_COLOR: Record<string, string> = {
  UNSCHEDULED: '#C4881A', SCHEDULED: '#5E6AD2', PICKED_UP: '#2594B8', IN_TRANSIT: '#D4772C',
  DELIVERED: '#4CAF82', PARTIALLY_DELIVERED: '#7B6FCC', FAILED: '#C7372F', CANCELLED: '#8A8F98',
};

const isoDay = (d: Date) => format(d, 'yyyy-MM-dd');
const effectiveDate = (d: CalDelivery) => d.rescheduledAt || d.scheduledAt || d.createdAt;

export default function OverviewCalendarPage() {
  const navigate = useNavigate();
  const t = useT();
  const [cursor, setCursor] = useState(() => new Date());
  const [selected, setSelected] = useState<string>(() => isoDay(new Date()));

  // Filters
  const [filterZone, setFilterZone] = useState<string>('all');
  const [filterDriver, setFilterDriver] = useState<string>('all');
  const [filterStatus, setFilterStatus] = useState<string>('all');

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

  const gridStart = useMemo(() => startOfWeek(startOfMonth(cursor), { weekStartsOn: 1 }), [cursor]);
  const gridEnd = useMemo(() => endOfWeek(endOfMonth(cursor), { weekStartsOn: 1 }), [cursor]);
  const rangeFrom = isoDay(gridStart);
  const rangeTo = isoDay(gridEnd);

  const { data: routes = [] } = useRoutes({ from: rangeFrom, to: rangeTo });
  const { data: deliveries = [] } = useQuery<CalDelivery[]>({
    queryKey: ['calendar-deliveries', rangeFrom, rangeTo],
    queryFn: async () => {
      const res = await api.get<CalDelivery[]>('/api/admin/deliveries/calendar', { params: { from: rangeFrom, to: rangeTo } });
      return Array.isArray(res.data) ? res.data : [];
    },
  });

  // Extract filter options dynamically from current range data
  const availableZones = useMemo(() => {
    const set = new Set<string>();
    deliveries.forEach(d => { if (d.zoneName) set.add(d.zoneName); });
    return Array.from(set).sort();
  }, [deliveries]);

  const availableDrivers = useMemo(() => {
    const set = new Set<string>();
    deliveries.forEach(d => { if (d.driverName) set.add(d.driverName); });
    routes.forEach(r => { if (r.driverName) set.add(r.driverName); });
    return Array.from(set).sort();
  }, [deliveries, routes]);

  const availableStatuses = useMemo(() => {
    const set = new Set<string>();
    deliveries.forEach(d => { if (d.status) set.add(d.status); });
    routes.forEach(r => { if (r.status) set.add(r.status); });
    return Array.from(set).sort();
  }, [deliveries, routes]);

  const filterAttributes = useMemo(() => [
    { key: 'zone',   label: t.deliveriesPage?.zoneHeader || 'Zone',   options: availableZones.map(z => ({ value: z, label: z })) },
    { key: 'driver', label: t.deliveriesPage?.driverHeader || 'Chauffeur', options: availableDrivers.map(d => ({ value: d, label: d })) },
    { key: 'status', label: t.deliveriesPage?.statusHeader || 'Statut', options: availableStatuses.map(s => ({ value: s, label: t.statusLabels[s] || s })) },
  ], [availableZones, availableDrivers, availableStatuses, t]);

  // Filtered datasets
  const filteredDeliveries = useMemo(() => {
    return deliveries.filter(d => {
      const matchZone = filterZone === 'all' || d.zoneName === filterZone;
      const matchDriver = filterDriver === 'all' || d.driverName === filterDriver;
      const matchStatus = filterStatus === 'all' || d.status === filterStatus;
      return matchZone && matchDriver && matchStatus;
    });
  }, [deliveries, filterZone, filterDriver, filterStatus]);

  const filteredRoutes = useMemo(() => {
    return routes.filter(r => {
      const matchDriver = filterDriver === 'all' || r.driverName === filterDriver;
      const matchStatus = filterStatus === 'all' || r.status === filterStatus;
      return matchDriver && matchStatus;
    });
  }, [routes, filterDriver, filterStatus]);

  // Bucket by day using filtered data
  const deliveriesByDay = useMemo(() => {
    const m = new Map<string, CalDelivery[]>();
    filteredDeliveries.forEach(d => {
      const ed = effectiveDate(d);
      if (!ed) return;
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

  // Build grid days
  const days = useMemo(() => {
    const arr: Date[] = [];
    let d = gridStart;
    while (d <= gridEnd) { arr.push(d); d = addDays(d, 1); }
    return arr;
  }, [gridStart, gridEnd]);

  const selectedDeliveries = deliveriesByDay.get(selected) ?? [];
  const selectedRoutes = routesByDay.get(selected) ?? [];

  const { totalDels, completedDels, completionRate, totalCod, totalWeight, totalRts } = useMemo(() => {
    const totalDels = selectedDeliveries.length;
    const completedDels = selectedDeliveries.filter(d => d.status === 'DELIVERED').length;
    const completionRate = totalDels > 0 ? Math.round((completedDels / totalDels) * 100) : 0;
    const totalCod = selectedDeliveries.reduce((sum, d) => sum + (d.totalAmount || 0), 0);
    const totalWeight = selectedDeliveries.reduce((sum, d) => sum + (d.totalWeightKg || 0), 0);
    const totalRts = selectedRoutes.length;
    return { totalDels, completedDels, completionRate, totalCod, totalWeight, totalRts };
  }, [selectedDeliveries, selectedRoutes]);

  const weekdayLabels = ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'];

  return (
    <div className="h-auto lg:h-[calc(100dvh-56px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Header */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-3 flex items-center justify-between max-w-[1800px] mx-auto">
          <div className="flex items-center gap-3">
            <IconCalendar size={18} className="text-[var(--brand)]" />
            <h1 className="text-lg font-bold text-[var(--text-primary)] capitalize">
              {format(cursor, 'MMMM yyyy', { locale: fr })}
            </h1>
          </div>
        </div>
      </div>

      <PageFilterBar
        attributes={filterAttributes}
        activeFilters={activeFiltersState}
        onFilterChange={handleFilterChange}
        extraActions={
          <div className="ml-auto flex items-center gap-2">
            <button onClick={() => setCursor(c => addMonths(c, -1))} className="w-8 h-8 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"><IconChevronLeft size={16} /></button>
            <button onClick={() => { setCursor(new Date()); setSelected(isoDay(new Date())); }} className="h-8 px-3 rounded-md border border-[var(--border)] text-sm font-bold text-[var(--text-secondary)] hover:bg-[var(--hover-bg)] transition-colors">Aujourd'hui</button>
            <button onClick={() => setCursor(c => addMonths(c, 1))} className="w-8 h-8 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"><IconChevronRight size={16} /></button>
          </div>
        }
      />

      <div className="flex flex-col lg:flex-row flex-1 min-h-0 max-w-[1800px] mx-auto w-full overflow-visible lg:overflow-hidden">
        {/* Calendar grid */}
        <div className="flex-1 flex flex-col p-4 min-w-0">
          <div className="grid grid-cols-7 gap-px mb-1">
            {weekdayLabels.map(w => (
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
              const isSelected = key === selected;
              // status mix dots (max 4 unique)
              const statusCounts = dels.reduce<Record<string, number>>((acc, d) => { acc[d.status] = (acc[d.status] ?? 0) + 1; return acc; }, {});
              return (
                <button
                  key={key}
                  onClick={() => setSelected(key)}
                  className={cn(
                    'flex flex-col items-start p-2 rounded-lg border text-left transition-colors overflow-hidden',
                    isSelected ? 'border-[var(--brand)] bg-[var(--hover-bg)]' : 'border-[var(--border)] hover:bg-[var(--hover-bg)]',
                    !inMonth && 'opacity-40',
                  )}
                  style={{ background: isSelected ? undefined : 'var(--surface)' }}
                >
                  <div className="flex items-center justify-between w-full">
                    <span className={cn('text-sm font-bold', isToday ? 'text-[var(--brand)]' : 'text-[var(--text-primary)]')}>
                      {format(day, 'd')}
                    </span>
                    {rts.length > 0 && (
                      <span className="inline-flex items-center gap-0.5 text-2xs font-bold text-[var(--text-muted)]">
                        <IconRoute size={10} /> {rts.length}
                      </span>
                    )}
                  </div>
                  {dels.length > 0 && (
                    <>
                      <div className="flex flex-wrap gap-0.5 mt-1.5">
                        {Object.entries(statusCounts).slice(0, 4).map(([s, n]) => (
                          <span key={s} className="inline-flex items-center gap-0.5 text-2xs font-bold px-1 rounded"
                                style={{ background: `${STATUS_COLOR[s] ?? '#888'}1a`, color: STATUS_COLOR[s] ?? '#888' }}>
                            {n}
                          </span>
                        ))}
                      </div>
                      <span className="mt-auto text-2xs font-semibold text-[var(--text-muted)]">{dels.length} livr.</span>
                    </>
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* Day detail panel */}
        <div className="w-full lg:w-[340px] border-t lg:border-t-0 lg:border-l border-[var(--border)] bg-[var(--surface)] shrink-0 flex flex-col overflow-visible lg:overflow-hidden">
          <div className="px-4 py-3 border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
            <p className="text-base font-bold text-[var(--text-primary)] capitalize">
              {format(parseISO(selected), 'EEEE d MMMM', { locale: fr })}
            </p>
            <p className="text-xs text-[var(--text-muted)]">{selectedDeliveries.length} livraison(s) · {selectedRoutes.length} tournée(s)</p>
          </div>

          {/* KPI Dashboard Grid */}
          <div className="px-3 py-3 border-b border-[var(--border)] shrink-0 bg-[var(--surface-sunken)]">
            <div className="grid grid-cols-2 gap-2">
              <KPICard
                label="Livr. Complétées"
                value={`${completionRate}%`}
                sub={`${completedDels} / ${totalDels}`}
                tone={completionRate >= 80 ? 'success' : completionRate >= 50 ? 'warning' : totalDels > 0 ? 'danger' : 'default'}
                className="pl-4 pr-2.5 py-3 h-[76px]"
              />
              <KPICard
                label="Montant Total"
                value={formatMoney(totalCod, selectedDeliveries[0]?.currency ?? 'TND')}
                sub="Valeur des colis"
                tone="success"
                className="pl-4 pr-2.5 py-3 h-[76px]"
              />
              <KPICard
                label="Poids Charge"
                value={`${totalWeight.toFixed(1)} kg`}
                sub="Charge estimée"
                tone="info"
                className="pl-4 pr-2.5 py-3 h-[76px]"
              />
              <KPICard
                label="Tournées"
                value={totalRts}
                sub={`${selectedRoutes.filter(r => r.status === 'IN_PROGRESS' || r.status === 'IN_TRANSIT').length} en cours`}
                tone="default"
                className="pl-4 pr-2.5 py-3 h-[76px]"
              />
            </div>
          </div>

          <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-4">
            {selectedRoutes.length > 0 && (
              <div>
                <p className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-2">Tournées planifiées</p>
                <div className="flex flex-col gap-1.5">
                  {selectedRoutes.map(r => (
                    <button key={r.id} onClick={() => navigate(`/routes/${r.id}`)}
                            className="flex items-center justify-between p-2.5 rounded-lg border border-[var(--border)] hover:bg-[var(--hover-bg)] text-left w-full transition-colors">
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-1.5 justify-between mb-1">
                          <p className="text-sm font-bold text-[var(--text-primary)] truncate">{r.name}</p>
                          <StatusBadge status={r.status} size="sm" />
                        </div>
                        <p className="text-xs text-[var(--text-muted)] truncate">{r.driverName ?? 'Non assigné'}</p>
                        <div className="flex items-center gap-2 mt-1.5 text-2xs text-[var(--text-muted)] font-medium">
                          <span>{r.stops?.length ?? 0} arrêts</span>
                          {r.totalDistanceMeters !== undefined && r.totalDistanceMeters > 0 && (
                            <>
                              <span>•</span>
                              <span>{(r.totalDistanceMeters / 1000).toFixed(1)} km</span>
                            </>
                          )}
                          {r.depotName && (
                            <>
                              <span>•</span>
                              <span className="truncate max-w-[120px]" title={r.depotName}>{r.depotName}</span>
                            </>
                          )}
                        </div>
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )}
            <div>
              <p className="text-2xs font-bold uppercase tracking-wider text-[var(--text-muted)] mb-2">Livraisons</p>
              {selectedDeliveries.length === 0 ? (
                <div className="flex flex-col items-center py-8 gap-2 opacity-40">
                  <IconPackage size={22} /><span className="text-xs font-semibold">Aucune livraison</span>
                </div>
              ) : (
                <div className="flex flex-col gap-1.5">
                  {selectedDeliveries.map(d => (
                    <button key={d.deliveryId} onClick={() => navigate(`/deliveries/${d.deliveryId}`)}
                            className="flex flex-col p-2.5 rounded-lg border border-[var(--border)] hover:bg-[var(--hover-bg)] text-left w-full gap-1 transition-colors">
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <p className="text-sm font-bold text-[var(--text-primary)] truncate">{d.clientName ?? d.orderRef ?? d.deliveryId.slice(0, 8)}</p>
                          {d.orderRef && (
                            <p className="text-2xs font-mono text-[var(--text-muted)]">{d.orderRef}</p>
                          )}
                        </div>
                        <StatusBadge status={d.status} size="sm" />
                      </div>
                      <p className="text-xs text-[var(--text-secondary)] truncate">
                        {d.dropoffCity ?? '—'}{d.driverName ? ` · ${d.driverName}` : ''}
                      </p>
                      {d.itemsSummary && (
                        <p className="text-2xs text-[var(--text-muted)] italic truncate" title={d.itemsSummary}>
                          {d.itemsSummary}
                        </p>
                      )}
                      <div className="flex items-center justify-between mt-1 pt-1 border-t border-[var(--border)] border-dashed text-2xs text-[var(--text-muted)] font-semibold">
                        <span>{d.totalWeightKg ? `${d.totalWeightKg.toFixed(1)} kg` : '— kg'}</span>
                        <span>{d.totalAmount ? formatMoney(d.totalAmount, d.currency ?? 'TND') : '—'}</span>
                      </div>
                      {d.status === 'FAILED' && d.failReason && (
                        <p className="text-2xs text-[var(--danger)] font-medium bg-red-50 px-1.5 py-0.5 rounded border border-red-100 mt-1">
                          Motif: {d.failReason}
                        </p>
                      )}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
