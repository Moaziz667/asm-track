import React from 'react';
import {
  IconPackage, IconUser, IconRoute, IconMapPin, IconCalendar, IconClock,
} from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
import { getDayBucket } from '@/lib/sla';
import StatusBadge from '@/components/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import type { DeliveryStatus } from '@/types';
import type { TranslationSchema } from '@/lib/LocaleContext';

// Loose queue-card row shape (dashboard summary + nested sub-deliveries for lots).
type CardItem = {
  deliveryId?: string; orderRef?: string; clientName?: string; city?: string;
  driverName?: string; routeId?: string; routeName?: string; routeRef?: string;
  scheduledAt?: string; slaHealth?: string; status?: string; deliveriesCount?: number;
  subDeliveries?: CardItem[];
};

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

function Chip({ icon, label, muted }: { icon: React.ReactNode; label: string; muted?: boolean }) {
  return (
    <span
      className="inline-flex items-center gap-1 text-2xs font-medium px-1.5 py-0.5 rounded border border-[var(--border)] bg-[var(--hover-bg)] leading-none select-none"
      style={{ color: muted ? 'var(--text-soft)' : 'var(--text-muted)' }}
    >
      {icon}
      <span className="truncate max-w-[105px]">{label}</span>
    </span>
  );
}

function ScheduledLine({ scheduledAt, status, t }: { scheduledAt?: string; status: DeliveryStatus; t: TranslationSchema }) {
  if (!scheduledAt) return null;
  const bucket = ['UNSCHEDULED', 'SCHEDULED', 'PICKED_UP', 'IN_TRANSIT'].includes(status)
    ? getDayBucket(scheduledAt)
    : null;
  const formatted = formatCardDate(scheduledAt);
  const tone = bucket === 'overdue' ? 'text-[var(--danger)]'
    : bucket === 'today' ? 'text-[var(--warning)]'
    : 'text-[var(--text-soft)]';
  const iconColor = bucket === 'overdue' ? 'var(--danger)'
    : bucket === 'today' ? 'var(--warning)'
    : 'var(--text-soft)';

  return (
    <div className={`flex items-center gap-1 text-2xs font-medium ${tone}`}>
      <IconCalendar size={10} stroke={2.5} style={{ color: iconColor }} />
      <span>{formatted}</span>
    </div>
  );
}

/** Single delivery card in the dispatch kanban. */
export function DeliveryCard({ d, status }: { d: CardItem; status: DeliveryStatus }) {
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
      className="w-full text-left p-3 rounded-md bg-[var(--surface)] border border-[var(--border)] cursor-pointer transition-colors hover:bg-[var(--hover-bg)] hover:border-[var(--border-strong)] focus:outline-none dispatch-card"
    >
      {/* Header: ref + scheduled */}
      <div className="flex items-center justify-between gap-2 mb-1.5">
        <span className="font-mono text-2xs font-semibold tracking-tight text-[var(--brand)] truncate">
          {d.orderRef || d.deliveryId?.slice(0, 8)}
        </span>
        <ScheduledLine scheduledAt={d.scheduledAt} status={status} t={t} />
      </div>

      {/* Client */}
      <p className="text-sm font-semibold text-[var(--text-primary)] leading-snug mb-1.5 truncate">
        {d.clientName || t.dashboardPage.unknownClient}
      </p>

      {/* City */}
      {d.city && (
        <div className="text-2xs text-[var(--text-muted)] font-medium mb-2 flex items-center gap-1">
          <IconMapPin size={11} stroke={2.5} className="shrink-0" />
          <span className="truncate">{d.city}</span>
        </div>
      )}

      {/* Status + SLA */}
      <div className="flex items-center gap-1.5 flex-wrap mb-2">
        <StatusBadge status={status} size="sm" />
        <SlaHealthBadge health={d.slaHealth} />
      </div>

      {/* Driver / route chips */}
      <div className="flex flex-wrap gap-1.5">
        {d.driverName && <Chip icon={<IconUser size={10.5} stroke={2.5} />} label={d.driverName} />}
        {routeLabel && <Chip icon={<IconRoute size={10.5} stroke={2.5} />} label={routeLabel} />}
      </div>
    </button>
  );
}

/** Lot (multi-delivery) card in the dispatch kanban. */
export function LotCard({ d, status }: { d: CardItem; status: DeliveryStatus }) {
  const t = useT();
  const deliveries = d.subDeliveries || [];
  const totalCount = d.deliveriesCount || deliveries.length;
  const completedCount = deliveries.filter((x: CardItem) => x.status === 'DELIVERED').length;
  const progress = totalCount > 0 ? (completedCount / totalCount) * 100 : 0;

  const handleClick = () => {
    const url = status === 'UNSCHEDULED' ? '/route-builder' : `/deliveries?lot=${d.orderRef}`;
    window.open(url, '_blank');
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className="w-full text-left p-3 rounded-md bg-[var(--surface)] border border-[var(--border)] cursor-pointer transition-colors hover:bg-[var(--hover-bg)] hover:border-[var(--border-strong)] focus:outline-none dispatch-card"
    >
      {/* Header: lot ref + count */}
      <div className="flex items-center justify-between gap-2 mb-1.5">
        <div className="flex items-center gap-1 min-w-0">
          <IconPackage size={11} className="shrink-0 text-[var(--text-muted)]" stroke={2.5} />
          <span className="font-mono text-2xs font-semibold tracking-tight text-[var(--text-muted)] truncate">
            {d.orderRef || 'LOT'}
          </span>
        </div>
        <span className="text-2xs font-mono font-semibold tabular-nums text-[var(--text-soft)] shrink-0">
          {completedCount}/{totalCount}
        </span>
      </div>

      {/* Client list */}
      <div className="flex flex-col gap-1 mb-2.5 min-w-0">
        {deliveries.slice(0, 2).map((x: CardItem, i: number) => (
          <p key={i} className="text-sm font-semibold text-[var(--text-primary)] leading-snug truncate">{x.clientName}</p>
        ))}
        {deliveries.length > 2 && (
          <p className="text-2xs font-medium text-[var(--text-soft)] mt-0.5">
            {t.dashboardPage.moreDeliveries.replace('{count}', String(deliveries.length - 2))}
          </p>
        )}
      </div>

      {/* Status + progress */}
      <div className="flex flex-col gap-2">
        <StatusBadge status={status} size="sm" />
        <div className="flex items-center gap-2">
          <div className="flex-1 h-1 bg-[var(--hover-bg)] rounded-full overflow-hidden">
            <div className="h-full rounded-full bg-[var(--success)]" style={{ width: `${progress}%` }} />
          </div>
        </div>
      </div>
    </button>
  );
}
