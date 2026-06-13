import React from 'react';
import { IconPackage, IconUser, IconRoute, IconMapPin, IconCalendar, IconClock } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
import { getDayBucket } from '@/lib/sla';
import type { DeliveryStatus } from '@/types';

const formatCardDate = (dateStr?: string) => {
  if (!dateStr) return '';
  try {
    const date = new Date(dateStr);
    return date.toLocaleDateString(undefined, { day: '2-digit', month: 'short' }) + ' ' +
      date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false });
  } catch {
    return dateStr.slice(5, 16).replace('T', ' ');
  }
};

/** Overdue/today SLA chip shown on pending kanban cards. */
function SlaChip({ scheduledAt, status }: { scheduledAt?: string; status: DeliveryStatus }) {
  if (!scheduledAt || !['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT'].includes(status)) return null;
  const bucket = getDayBucket(scheduledAt);
  if (bucket === 'overdue') {
    return (
      <div className="mb-2">
        <span className="text-2xs font-bold px-1.5 py-0.5 rounded border border-[var(--danger)] bg-[var(--danger-bg)] text-[var(--danger)] inline-flex items-center gap-1">
          <IconClock size={11} stroke={2.5} /> En retard (Planifié)
        </span>
      </div>
    );
  }
  if (bucket === 'today') {
    return (
      <div className="mb-2">
        <span className="text-2xs font-bold px-1.5 py-0.5 rounded border border-[var(--warning)] bg-[var(--warning-bg)] text-[var(--warning)] inline-flex items-center gap-1">
          <IconClock size={11} stroke={2.5} /> Planifié Auj.
        </span>
      </div>
    );
  }
  return null;
}

function Chip({ icon, label, muted }: { icon: React.ReactNode; label: string; muted?: boolean }) {
  return (
    <span
      className="inline-flex items-center gap-1.5 text-2xs font-bold px-2 py-0.5 rounded-sm border border-[var(--border)] bg-[var(--hover-bg)] leading-none select-none transition-all"
      style={{ color: muted ? 'var(--text-soft)' : 'var(--text-muted)' }}
    >
      {icon}
      <span className="truncate max-w-[105px]">{label}</span>
    </span>
  );
}

/** Single delivery card in the dispatch kanban. */
export function DeliveryCard({ d, status, color }: { d: any; status: DeliveryStatus; color: string }) {
  const t = useT();
  const handleClick = () => {
    let url = '';
    if (status === 'UNSCHEDULED') url = '/route-builder';
    else if (status === 'DELIVERED' || status === 'PARTIALLY_DELIVERED') url = `/deliveries/${d.deliveryId}`;
    else if (d.routeId) url = `/routes/${d.routeId}`;
    else url = `/deliveries/${d.deliveryId}`;
    window.open(url, '_blank');
  };
  const routeLabel = d.routeName || d.routeRef || (d.routeId ? `Route #${d.routeId.slice(0, 5)}` : null);

  return (
    <button
      type="button"
      onClick={handleClick}
      className="w-full text-left p-3.5 rounded-lg bg-[var(--surface)] cursor-pointer focus:outline-none transition-all hover:shadow-sm hover:brightness-[0.98] dispatch-card active:scale-[0.99]"
      style={{ border: '1px solid var(--border)', borderLeft: `4px solid ${color}` }}
    >
      <div className="mb-2 flex items-center justify-between border-b border-[var(--border)]/30 pb-1.5">
        <span className="font-mono text-2xs font-bold tracking-tight" style={{ color }}>
          {d.orderRef || d.deliveryId?.slice(0, 8)}
        </span>
        {d.scheduledAt && (
          <span className="flex items-center gap-1 text-2xs font-bold text-[var(--text-soft)]">
            <IconCalendar size={10} stroke={2.5} />
            <span>{formatCardDate(d.scheduledAt)}</span>
          </span>
        )}
      </div>

      <p className="text-base font-bold text-[var(--text-primary)] leading-snug mb-1.5 truncate">
        {d.clientName || t.dashboardPage.unknownClient}
      </p>

      {d.city && (
        <div className="text-2xs text-[var(--text-soft)] font-semibold mb-2.5 flex items-center gap-1">
          <IconMapPin size={11} stroke={2.5} className="text-primary shrink-0" />
          <span className="truncate">{d.city}</span>
        </div>
      )}

      <SlaChip scheduledAt={d.scheduledAt} status={status} />

      <div className="flex flex-wrap gap-1.5 pt-0.5">
        {d.driverName && <Chip icon={<IconUser size={10.5} stroke={2.5} />} label={d.driverName} />}
        {routeLabel && <Chip icon={<IconRoute size={10.5} stroke={2.5} />} label={routeLabel} />}
      </div>
    </button>
  );
}

/** Lot (multi-delivery) card in the dispatch kanban. */
export function LotCard({ d, status, color }: { d: any; status: DeliveryStatus; color: string }) {
  const t = useT();
  const deliveries = d.subDeliveries || [];
  const totalCount = d.deliveriesCount || deliveries.length;
  const completedCount = deliveries.filter((x: any) => x.status === 'DELIVERED').length;
  const progress = totalCount > 0 ? (completedCount / totalCount) * 100 : 0;

  const handleClick = () => {
    const url = status === 'UNSCHEDULED' ? '/route-builder' : `/deliveries?lot=${d.orderRef}`;
    window.open(url, '_blank');
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className="w-full text-left p-3.5 rounded-lg bg-[var(--surface)] cursor-pointer focus:outline-none transition-all hover:shadow-sm hover:brightness-[0.98] dispatch-card active:scale-[0.99]"
      style={{ border: '1px solid var(--border)', borderLeft: `4px solid ${color}` }}
    >
      <div className="mb-2 flex items-center justify-between border-b border-[var(--border)]/30 pb-1.5">
        <div className="flex items-center gap-1">
          <IconPackage size={10.5} style={{ color }} stroke={2.5} />
          <span className="font-mono text-2xs font-bold tracking-tight" style={{ color }}>{d.orderRef || 'LOT'}</span>
        </div>
        {d.scheduledAt && (
          <span className="flex items-center gap-1 text-2xs font-bold text-[var(--text-soft)]">
            <IconCalendar size={10} stroke={2.5} />
            <span>{formatCardDate(d.scheduledAt)}</span>
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1 mb-2.5 min-w-0">
        {deliveries.slice(0, 2).map((x: any, i: number) => (
          <p key={i} className="text-base font-bold text-[var(--text-primary)] leading-snug truncate">{x.clientName}</p>
        ))}
        {deliveries.length > 2 && (
          <p className="text-2xs font-bold text-[var(--text-soft)] mt-0.5">
            {t.dashboardPage.moreDeliveries.replace('{count}', String(deliveries.length - 2))}
          </p>
        )}
      </div>

      <SlaChip scheduledAt={d.scheduledAt} status={status} />

      <div className="flex items-center gap-2.5">
        <div className="flex-1 h-1 bg-[var(--border)]/65 rounded-full overflow-hidden">
          <div className="h-full transition-all duration-500 rounded-full" style={{ width: `${progress}%`, backgroundColor: color }} />
        </div>
        <span className="text-2xs font-bold font-mono tabular-nums shrink-0" style={{ color }}>{completedCount}/{totalCount}</span>
      </div>
    </button>
  );
}
