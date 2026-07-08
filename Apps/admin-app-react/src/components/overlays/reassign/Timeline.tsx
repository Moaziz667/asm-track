import { IconCheck, IconCornerDownRight, IconMapPin, IconPackage } from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import type { Stop, ReassignTarget } from './types';
import { DONE, CURRENT, winLabel } from './helpers';

/** Insertion timeline: the route's stops with clickable slots between them. The new stop preview lands
 *  at `effectiveOrder`; a depot PICKUP shows as a greyed, non-selectable landmark. */
export function Timeline({ stops, effectiveOrder, target, winLabelText, onPick, t }: {
  stops: Stop[]; effectiveOrder: number; target: ReassignTarget | null; winLabelText: string | null;
  onPick: (order: number) => void; t: ReturnType<typeof useT>;
}) {
  const floorOrder = stops.reduce((m, s) => DONE.has(s.status) ? s.stopOrder + 1 : m, 1);
  const appendOrder = (stops[stops.length - 1]?.stopOrder ?? 0) + 1;
  const newCard = (
    <div className="flex items-center gap-2 py-1.5 ps-1 my-0.5 rounded-md" style={{ background: 'var(--brand-bg)' }}>
      <span className="flex items-center justify-center w-6 h-6 rounded-full bg-[var(--brand)] text-white shrink-0"><IconMapPin size={12} /></span>
      <div className="min-w-0 flex-1">
        <p className="text-xs font-semibold text-[var(--brand)] truncate">{target?.orderRef || target?.clientName || t.configureInsertion.newStop} · {t.assignFlow.insertPosition.replace('{pos}', String(effectiveOrder))}</p>
      </div>
      {winLabelText && <span className="text-2xs font-medium text-[var(--brand)] tabular-nums shrink-0">{winLabelText}</span>}
    </div>
  );
  const slot = (order: number) => <InsertSlot active={effectiveOrder === order} onPick={() => onPick(order)} label={t.configureInsertion.insertHere} />;
  return (
    <div className="flex flex-col rounded-xl border border-[var(--border)] p-2">
      {stops.map((s) => (
        <div key={s.id}>
          {s.stopOrder >= floorOrder && (effectiveOrder === s.stopOrder ? newCard : slot(s.stopOrder))}
          <StopRow stop={s} done={DONE.has(s.status)} current={CURRENT.has(s.status)} t={t} />
        </div>
      ))}
      {effectiveOrder === appendOrder ? newCard : slot(appendOrder)}
    </div>
  );
}

function StopRow({ stop, done, current, t }: { stop: Stop; done: boolean; current: boolean; t: ReturnType<typeof useT> }) {
  const w = winLabel(stop.startTimeWindow, stop.endTimeWindow);
  // Depot PICKUP: a load operation, not a delivery. Render it as a greyed, non-selectable context
  // landmark (keeps the sequence readable) — the InsertSlots around it stay the only drop targets.
  if (stop.stopType === 'PICKUP') {
    return (
      <div className="flex items-center gap-2.5 py-1.5" style={{ opacity: 0.55 }}>
        <span className="flex items-center justify-center w-6 h-6 rounded-full shrink-0" style={{ background: 'var(--surface-sunken)' }}>
          <IconPackage size={12} className="text-[var(--text-muted)]" />
        </span>
        <div className="flex-1 min-w-0">
          <span className="text-xs font-semibold text-[var(--text-secondary)] truncate">
            {t.configureInsertion.pickupStop}{stop.sourceDepotName ? ` — ${stop.sourceDepotName}` : ''}
          </span>
        </div>
      </div>
    );
  }
  // Pickup (multi-depot load) stops carry no deliveryId/orderRef — label them as a load, don't crash.
  const ref = stop.orderRef || (stop.deliveryId ? stop.deliveryId.slice(0, 8).toUpperCase() : t.configureInsertion.pickupStop);
  return (
    <div className={cn('flex items-center gap-2.5 py-1.5', current && 'rounded-md px-1.5 -mx-1.5')} style={{ opacity: done ? 0.5 : 1, background: current ? 'var(--surface-sunken)' : undefined }}>
      <span className="flex items-center justify-center w-6 h-6 rounded-full text-2xs font-bold shrink-0 tabular-nums text-[var(--text-muted)]" style={{ background: 'var(--surface-sunken)' }}>
        {done ? <IconCheck size={12} /> : stop.stopOrder}
      </span>
      <div className="flex-1 min-w-0">
        <span className="text-xs font-semibold text-[var(--text-primary)] truncate font-mono">{ref}</span>
        <p className="text-2xs text-[var(--text-muted)] truncate">{stop.clientName || stop.deliveryCity || '—'}</p>
      </div>
      <div className="text-end shrink-0">
        {w && <p className="text-2xs font-medium text-[var(--text-secondary)] tabular-nums">{w}</p>}
        <p className="text-2xs text-[var(--text-soft)]">{done ? t.configureInsertion.statusDone : current ? t.configureInsertion.statusCurrent : t.configureInsertion.statusPending}</p>
      </div>
    </div>
  );
}

function InsertSlot({ active, onPick, label }: { active: boolean; onPick: () => void; label: string }) {
  return (
    <button type="button" onClick={onPick} className={cn('group flex items-center gap-2 py-1 w-full transition-colors', active ? 'text-[var(--brand)]' : 'text-[var(--text-soft)] hover:text-[var(--text-secondary)]')}>
      <span className="flex-1 h-px" style={{ background: active ? 'var(--brand)' : 'var(--border)' }} />
      <span className={cn('inline-flex items-center gap-1 text-2xs font-semibold px-2 py-0.5 rounded-full border', active ? 'border-[var(--brand)] bg-[var(--brand)] text-white' : 'border-[var(--border)]')}>
        {active ? <IconCheck size={11} /> : <IconCornerDownRight size={11} />}{label}
      </span>
      <span className="flex-1 h-px" style={{ background: active ? 'var(--brand)' : 'var(--border)' }} />
    </button>
  );
}
