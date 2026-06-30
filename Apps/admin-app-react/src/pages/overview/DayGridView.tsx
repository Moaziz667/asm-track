import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { parseISO, isSameDay, format } from 'date-fns';
import { fr } from 'date-fns/locale';
import { IconPackageOff } from '@tabler/icons-react';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import type { useT } from '@/lib/LocaleContext';
import { CalDelivery, STATUS_COLOR, timeOfDay } from './shared';

interface Props {
  selected: string;
  deliveries: CalDelivery[];
  t: ReturnType<typeof useT>;
}

const START_HOUR = 6;
const END_HOUR = 21;
const HOUR_H = 54;           // px per hour row
const SLOT_MIN = 30;         // visual duration of a delivery block
const GRID_H = (END_HOUR - START_HOUR) * HOUR_H;

interface Col { key: string; driverId?: string; name: string; }
interface Block extends CalDelivery { startMin: number; endMin: number; lane: number; lanes: number; }

/** Greedy lane layout: cluster transitively-overlapping blocks, split column width across lanes. */
function layoutColumn(items: (CalDelivery & { startMin: number; endMin: number })[]): Block[] {
  const sorted = [...items].sort((a, b) => a.startMin - b.startMin);
  const out: Block[] = [];
  let cluster: (CalDelivery & { startMin: number; endMin: number; lane?: number })[] = [];
  let clusterEnd = -1;
  const flush = () => {
    const lanesEnd: number[] = [];
    for (const it of cluster) {
      let placed = false;
      for (let i = 0; i < lanesEnd.length; i++) {
        if (lanesEnd[i] <= it.startMin) { it.lane = i; lanesEnd[i] = it.endMin; placed = true; break; }
      }
      if (!placed) { it.lane = lanesEnd.length; lanesEnd.push(it.endMin); }
    }
    const lanes = lanesEnd.length || 1;
    for (const it of cluster) out.push({ ...(it as CalDelivery), startMin: it.startMin, endMin: it.endMin, lane: it.lane ?? 0, lanes });
    cluster = []; clusterEnd = -1;
  };
  for (const it of sorted) {
    if (cluster.length && it.startMin >= clusterEnd) flush();
    cluster.push(it); clusterEnd = Math.max(clusterEnd, it.endMin);
  }
  if (cluster.length) flush();
  return out;
}

export function DayGridView({ selected, deliveries, t }: Props) {
  const navigate = useNavigate();
  const day = parseISO(selected);
  const isToday = isSameDay(day, new Date());

  // Build driver columns (+ unassigned bucket last).
  const columns = useMemo<Col[]>(() => {
    const map = new Map<string, Col>();
    for (const d of deliveries) {
      const key = d.driverId ?? (d.driverName ? `name:${d.driverName}` : 'unassigned');
      if (!map.has(key)) map.set(key, { key, driverId: d.driverId, name: d.driverName ?? '' });
    }
    const cols = Array.from(map.values()).filter(c => c.key !== 'unassigned').sort((a, b) => a.name.localeCompare(b.name));
    if (map.has('unassigned') || deliveries.some(d => !d.driverId && !d.driverName)) {
      cols.push({ key: 'unassigned', name: t.overviewPage?.unassigned ?? 'Non assigné' });
    }
    return cols.length ? cols : [{ key: 'unassigned', name: t.overviewPage?.unassigned ?? 'Non assigné' }];
  }, [deliveries, t]);

  // Partition each column's deliveries into timed (positioned) vs untimed (top strip).
  const perCol = useMemo(() => {
    return columns.map(col => {
      const mine = deliveries.filter(d => (d.driverId ?? (d.driverName ? `name:${d.driverName}` : 'unassigned')) === col.key);
      const timed: (CalDelivery & { startMin: number; endMin: number })[] = [];
      const untimed: CalDelivery[] = [];
      for (const d of mine) {
        const tod = timeOfDay(d);
        if (!tod) { untimed.push(d); continue; }
        const startMin = Math.max(START_HOUR * 60, Math.min(END_HOUR * 60 - SLOT_MIN, tod.hour * 60 + tod.minute));
        timed.push({ ...d, startMin, endMin: startMin + SLOT_MIN });
      }
      return { col, blocks: layoutColumn(timed), untimed };
    });
  }, [columns, deliveries]);

  const hours = Array.from({ length: END_HOUR - START_HOUR + 1 }, (_, i) => START_HOUR + i);
  const nowTop = (() => {
    if (!isToday) return null;
    const now = new Date();
    const min = now.getHours() * 60 + now.getMinutes();
    if (min < START_HOUR * 60 || min > END_HOUR * 60) return null;
    return ((min - START_HOUR * 60) / 60) * HOUR_H;
  })();

  if (deliveries.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-3 text-[var(--text-muted)]">
        <IconPackageOff size={34} className="opacity-40" />
        <p className="text-sm font-semibold">{t.overviewPage?.noDeliveriesDay ?? 'Aucune livraison ce jour'}</p>
        <p className="text-xs capitalize">{format(day, 'EEEE d MMMM yyyy', { locale: fr })}</p>
      </div>
    );
  }

  return (
    <div className="flex-1 min-h-0 max-w-[1800px] mx-auto w-full overflow-auto">
      <div className="flex min-w-fit">
        {/* Hour gutter */}
        <div className="sticky left-0 z-20 shrink-0 w-14 bg-[var(--app-bg)]">
          <div className="h-[52px] border-b border-[var(--border)]" /> {/* header spacer */}
          <div className="relative" style={{ height: GRID_H }}>
            {hours.map((h, i) => (
              <div key={h} className="absolute right-2 text-2xs font-bold text-[var(--text-muted)] tabular-nums -translate-y-1/2"
                style={{ top: i * HOUR_H }}>{String(h).padStart(2, '0')}:00</div>
            ))}
          </div>
        </div>

        {/* Driver columns */}
        <div className="flex-1 flex">
          {perCol.map(({ col, blocks, untimed }) => (
            <div key={col.key} className="flex-1 min-w-[180px] border-l border-[var(--border)]">
              {/* Column header */}
              <div className="h-[52px] border-b border-[var(--border)] bg-[var(--surface)] sticky top-0 z-10 px-2 flex items-center gap-2">
                {col.key !== 'unassigned'
                  ? <DriverAvatarById driverId={col.driverId} name={col.name} size={26} />
                  : <span className="w-[26px] h-[26px] rounded-full bg-[var(--surface-sunken)] border border-[var(--border)] flex items-center justify-center text-[var(--text-muted)]"><IconPackageOff size={13} /></span>}
                <div className="min-w-0">
                  <p className="text-xs font-bold text-[var(--text-primary)] truncate">{col.name || (t.overviewPage?.unassigned ?? 'Non assigné')}</p>
                  <p className="text-3xs text-[var(--text-muted)]">{blocks.length + untimed.length} {t.overviewPage?.delAbbrev ?? 'livr.'}</p>
                </div>
              </div>

              {/* Untimed strip */}
              {untimed.length > 0 && (
                <div className="px-1.5 py-1 border-b border-dashed border-[var(--border)] bg-[var(--surface-sunken)] flex flex-wrap gap-1">
                  {untimed.map(d => (
                    <button key={d.deliveryId} onClick={() => navigate(`/deliveries/${d.deliveryId}`)} title={d.clientName ?? d.orderRef ?? ''}
                      className="text-3xs font-bold px-1.5 py-0.5 rounded truncate max-w-full"
                      style={{ background: `${STATUS_COLOR[d.status] ?? '#888'}1a`, color: STATUS_COLOR[d.status] ?? '#888' }}>
                      {d.clientName ?? d.orderRef ?? d.deliveryId.slice(0, 6)}
                    </button>
                  ))}
                </div>
              )}

              {/* Time grid */}
              <div className="relative" style={{ height: GRID_H }}>
                {hours.slice(0, -1).map((h, i) => (
                  <div key={h} className="absolute left-0 right-0 border-b border-[var(--border)]" style={{ top: (i + 1) * HOUR_H }} />
                ))}
                {nowTop != null && (
                  <div className="absolute left-0 right-0 z-10 pointer-events-none" style={{ top: nowTop }}>
                    <div className="h-[2px]" style={{ background: 'var(--danger)' }} />
                  </div>
                )}
                {blocks.map(b => {
                  const top = ((b.startMin - START_HOUR * 60) / 60) * HOUR_H;
                  const height = Math.max(30, ((b.endMin - b.startMin) / 60) * HOUR_H - 2);
                  const widthPct = 100 / b.lanes;
                  const color = STATUS_COLOR[b.status] ?? '#888';
                  return (
                    <button key={b.deliveryId} onClick={() => navigate(`/deliveries/${b.deliveryId}`)}
                      title={`${b.clientName ?? ''} · ${format(new Date(b.routeEtaAt || b.scheduledAt!), 'HH:mm')}`}
                      className="absolute rounded-md border text-left px-1.5 py-1 overflow-hidden hover:z-20 hover:shadow-md transition-shadow"
                      style={{
                        top, height, left: `calc(${b.lane * widthPct}% + 2px)`, width: `calc(${widthPct}% - 4px)`,
                        background: `${color}1f`, borderColor: `${color}55`, borderLeft: `3px solid ${color}`,
                      }}>
                      <p className="text-3xs font-bold tabular-nums" style={{ color }}>
                        {format(new Date(b.routeEtaAt || b.scheduledAt!), 'HH:mm')}
                      </p>
                      <p className="text-3xs font-semibold text-[var(--text-primary)] truncate leading-tight">{b.clientName ?? b.orderRef ?? '—'}</p>
                      {height > 44 && b.dropoffCity && <p className="text-3xs text-[var(--text-muted)] truncate">{b.dropoffCity}</p>}
                    </button>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
