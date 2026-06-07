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
import { cn } from '@/lib/utils';

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
}

const STATUS_COLOR: Record<string, string> = {
  UNSCHEDULED: '#C4881A', SCHEDULED: '#5E6AD2', PICKED_UP: '#2594B8', IN_TRANSIT: '#D4772C',
  DELIVERED: '#4CAF82', PARTIALLY_DELIVERED: '#7B6FCC', FAILED: '#C7372F', CANCELLED: '#8A8F98',
};

const isoDay = (d: Date) => format(d, 'yyyy-MM-dd');
const effectiveDate = (d: CalDelivery) => d.rescheduledAt || d.scheduledAt || d.createdAt;

export default function OverviewCalendarPage() {
  const navigate = useNavigate();
  const [cursor, setCursor] = useState(() => new Date());
  const [selected, setSelected] = useState<string>(() => isoDay(new Date()));

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

  // Bucket by day
  const deliveriesByDay = useMemo(() => {
    const m = new Map<string, CalDelivery[]>();
    deliveries.forEach(d => {
      const ed = effectiveDate(d);
      if (!ed) return;
      const key = ed.slice(0, 10);
      (m.get(key) ?? m.set(key, []).get(key)!).push(d);
    });
    return m;
  }, [deliveries]);

  const routesByDay = useMemo(() => {
    const m = new Map<string, RouteItem[]>();
    routes.forEach(r => {
      if (!r.date) return;
      const key = r.date.slice(0, 10);
      (m.get(key) ?? m.set(key, []).get(key)!).push(r);
    });
    return m;
  }, [routes]);

  // Build grid days
  const days = useMemo(() => {
    const arr: Date[] = [];
    let d = gridStart;
    while (d <= gridEnd) { arr.push(d); d = addDays(d, 1); }
    return arr;
  }, [gridStart, gridEnd]);

  const selectedDeliveries = deliveriesByDay.get(selected) ?? [];
  const selectedRoutes = routesByDay.get(selected) ?? [];

  const weekdayLabels = ['Lun', 'Mar', 'Mer', 'Jeu', 'Ven', 'Sam', 'Dim'];

  return (
    <div className="h-[calc(100vh-64px)] flex flex-col" style={{ background: 'var(--app-bg)' }}>
      {/* Header */}
      <div className="border-b border-[var(--border)] bg-[var(--surface)] shrink-0">
        <div className="px-6 py-4 flex items-center justify-between max-w-[1800px] mx-auto">
          <div className="flex items-center gap-3">
            <IconCalendar size={18} className="text-[var(--brand)]" />
            <h1 className="text-[15px] font-bold text-[var(--text-primary)] capitalize">
              {format(cursor, 'MMMM yyyy', { locale: fr })}
            </h1>
          </div>
          <div className="flex items-center gap-1.5">
            <button onClick={() => setCursor(c => addMonths(c, -1))} className="w-8 h-8 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]"><IconChevronLeft size={16} /></button>
            <button onClick={() => { setCursor(new Date()); setSelected(isoDay(new Date())); }} className="h-8 px-3 rounded-md border border-[var(--border)] text-[12px] font-bold text-[var(--text-secondary)] hover:bg-[var(--hover-bg)]">Aujourd'hui</button>
            <button onClick={() => setCursor(c => addMonths(c, 1))} className="w-8 h-8 flex items-center justify-center rounded-md border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)]"><IconChevronRight size={16} /></button>
          </div>
        </div>
      </div>

      <div className="flex flex-1 min-h-0 max-w-[1800px] mx-auto w-full">
        {/* Calendar grid */}
        <div className="flex-1 flex flex-col p-4 min-w-0">
          <div className="grid grid-cols-7 gap-px mb-1">
            {weekdayLabels.map(w => (
              <div key={w} className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] text-center py-1">{w}</div>
            ))}
          </div>
          <div className="grid grid-cols-7 gap-1 flex-1 auto-rows-fr">
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
                    <span className={cn('text-[12px] font-bold', isToday ? 'text-[var(--brand)]' : 'text-[var(--text-primary)]')}>
                      {format(day, 'd')}
                    </span>
                    {rts.length > 0 && (
                      <span className="inline-flex items-center gap-0.5 text-[9px] font-bold text-[var(--text-muted)]">
                        <IconRoute size={10} /> {rts.length}
                      </span>
                    )}
                  </div>
                  {dels.length > 0 && (
                    <>
                      <div className="flex flex-wrap gap-0.5 mt-1.5">
                        {Object.entries(statusCounts).slice(0, 4).map(([s, n]) => (
                          <span key={s} className="inline-flex items-center gap-0.5 text-[9px] font-bold px-1 rounded"
                                style={{ background: `${STATUS_COLOR[s] ?? '#888'}1a`, color: STATUS_COLOR[s] ?? '#888' }}>
                            {n}
                          </span>
                        ))}
                      </div>
                      <span className="mt-auto text-[10px] font-semibold text-[var(--text-muted)]">{dels.length} livr.</span>
                    </>
                  )}
                </button>
              );
            })}
          </div>
        </div>

        {/* Day detail panel */}
        <div className="w-[340px] border-l border-[var(--border)] bg-[var(--surface)] shrink-0 flex flex-col overflow-hidden">
          <div className="px-4 py-3 border-b border-[var(--border)]">
            <p className="text-[13px] font-bold text-[var(--text-primary)] capitalize">
              {format(parseISO(selected), 'EEEE d MMMM', { locale: fr })}
            </p>
            <p className="text-[11px] text-[var(--text-muted)]">{selectedDeliveries.length} livraison(s) · {selectedRoutes.length} tournée(s)</p>
          </div>
          <div className="flex-1 overflow-y-auto p-3 flex flex-col gap-4">
            {selectedRoutes.length > 0 && (
              <div>
                <p className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] mb-2">Tournées planifiées</p>
                <div className="flex flex-col gap-1.5">
                  {selectedRoutes.map(r => (
                    <button key={r.id} onClick={() => navigate(`/routes/${r.id}`)}
                            className="flex items-center justify-between p-2.5 rounded-lg border border-[var(--border)] hover:bg-[var(--hover-bg)] text-left">
                      <div className="min-w-0">
                        <p className="text-[12px] font-bold text-[var(--text-primary)] truncate">{r.name}</p>
                        <p className="text-[11px] text-[var(--text-muted)] truncate">{r.driverName ?? 'Non assigné'} · {r.stops?.length ?? 0} arrêts</p>
                      </div>
                      <IconRoute size={14} className="text-[var(--text-muted)] shrink-0" />
                    </button>
                  ))}
                </div>
              </div>
            )}
            <div>
              <p className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] mb-2">Livraisons</p>
              {selectedDeliveries.length === 0 ? (
                <div className="flex flex-col items-center py-8 gap-2 opacity-40">
                  <IconPackage size={22} /><span className="text-[11px] font-semibold">Aucune livraison</span>
                </div>
              ) : (
                <div className="flex flex-col gap-1.5">
                  {selectedDeliveries.map(d => (
                    <button key={d.deliveryId} onClick={() => navigate(`/deliveries/${d.deliveryId}`)}
                            className="flex items-center justify-between p-2.5 rounded-lg border border-[var(--border)] hover:bg-[var(--hover-bg)] text-left">
                      <div className="min-w-0">
                        <p className="text-[12px] font-bold text-[var(--text-primary)] truncate">{d.clientName ?? d.orderRef ?? d.deliveryId.slice(0, 8)}</p>
                        <p className="text-[11px] text-[var(--text-muted)] truncate">{d.dropoffCity ?? '—'}{d.driverName ? ` · ${d.driverName}` : ''}</p>
                      </div>
                      <span className="text-[9px] font-bold px-1.5 py-0.5 rounded shrink-0"
                            style={{ background: `${STATUS_COLOR[d.status] ?? '#888'}1a`, color: STATUS_COLOR[d.status] ?? '#888' }}>
                        {d.status}
                      </span>
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
