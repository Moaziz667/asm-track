import React, { useState } from 'react';
import type { TranslationSchema } from '@/lib/i18n/LocaleContext';
import { IconPackageExport } from '@tabler/icons-react';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import { AppLoader } from '@/components/AppLoader';
import { HandoffOpenTable } from './HandoffOpenTable';
import type { StatusValue } from '@/components/data-display/StatusBadge';
import { isHandoffOverdue } from '../hooks/useHandoffs';
import type { HandoffItem } from '../types';
import { HandoffHistoryTable } from './HandoffHistoryTable';

interface Props {
  open: HandoffItem[];
  history: HandoffItem[];
  loading: boolean;
  isReadOnly: boolean;
  cancellingId: string | null;
  onCancel: (id: string, reason: string) => Promise<boolean>;
  t: TranslationSchema;
}

type Segment = 'open' | 'history';

/**
 * The transfer phase drives everything visual on the card: the
 * custody-connector treatment, and the checkpoint icon. One derivation, no drift.
 *  - wait      → requested, sender hasn't shown the code yet
 *  - active    → code ready, awaiting the receiver's scan (the parcel is "in motion")
 *  - overdue   → open past its SLA
 *  - confirmed → received (terminal, success)
 *  - expired   → timed out (terminal)
 *  - cancelled → aborted by dispatch (terminal)
 */
export type Phase = 'wait' | 'active' | 'overdue' | 'confirmed' | 'expired' | 'cancelled';

export function phaseOf(h: HandoffItem, pendingMinutes?: number): Phase {
  if (h.state === 'CONFIRMED') return 'confirmed';
  if (h.state === 'EXPIRED') return 'expired';
  if (h.state === 'CANCELLED') return 'cancelled';
  if (isHandoffOverdue(h, pendingMinutes)) return 'overdue';
  if (h.state === 'IN_PROGRESS') return 'active';
  return 'wait';
}

export interface CardView {
  status: StatusValue;
  label: string;
  accent: string;
  phase: Phase;
}

export function cardView(h: HandoffItem, t: TranslationSchema, pendingMinutes?: number): CardView {
  const c = t.dispatchDeskPage;
  const phase = phaseOf(h, pendingMinutes);
  switch (phase) {
    case 'confirmed': return { status: 'COMPLETED', label: c.handoffStateConfirmed, accent: 'var(--success)', phase };
    case 'expired':   return { status: 'PENDING' as StatusValue, label: c.handoffStateExpired, accent: 'var(--warning)', phase };
    case 'cancelled': return { status: 'CANCELLED', label: c.handoffStateCancelled, accent: 'var(--text-soft)', phase };
    case 'overdue':   return { status: 'FAILED', label: c.handoffStateOverdue, accent: 'var(--danger)', phase };
    case 'active':    return { status: 'IN_PROGRESS', label: c.handoffStateInProgress, accent: 'var(--brand)', phase };
    default:          return { status: 'REQUESTED' as StatusValue, label: c.handoffStateRequested, accent: 'var(--info)', phase };
  }
}



/** "Bouclé en X" for a confirmed transfer (confirmedAt − requestedAt). */

/**
 * The custody connector — the signature element of a handoff card. It reads left→right
 * as "sender hands the parcel to receiver", and the LINE itself encodes the live state:
 * a marching dashed line while the parcel is in motion (awaiting scan), a solid line once
 * received, a broken segment when it failed. A centred checkpoint chip carries the phase
 * icon. This replaces color-only meaning — the badge above still names the state in words.
 */

/** Stable checkpoint icon — extracted to avoid remount on every render. */



/** Live countdown to the one-time code expiry (only while IN_PROGRESS). */


/** The full lifecycle trail: Requested → Code ready → Accepted / Expired / Cancelled. */

/** Vertical dot+line lifecycle trail — each step is a filled/hollow dot with a connecting
 *  rail, label, actor, and timestamp. No StatusBadge — the dot colour + text weight carry meaning. */

/** Scoped styles for the custody connector. Animation conveys live state (parcel in
 *  motion); honors prefers-reduced-motion by freezing the dashes. */
const CONNECTOR_CSS = `
.ho-conn{position:absolute;left:0;right:0;height:2px;border-radius:2px;
  background-image:linear-gradient(90deg,var(--ho-c) 0 55%,transparent 0);
  background-size:7px 2px;background-repeat:repeat-x;opacity:.85;}
.ho-conn--solid{background-image:none;background-color:var(--ho-c);opacity:.9;}
.ho-conn--broken{opacity:.55;}
.ho-conn--march{animation:hoMarch .55s linear infinite;}
@keyframes hoMarch{to{background-position-x:7px;}}
@media (prefers-reduced-motion: reduce){.ho-conn--march{animation:none;}}
`;

/**
 * A single handoff card. Owns its OWN expand state so opening "Détails" on one card
 * never touches another (a shared expanded-id can leak across cards if the API ever
 * returns colliding/empty ids — local state can't).
 */

export function HandoffCards({ open, history, loading, isReadOnly, cancellingId, onCancel, t }: Props) {
  const [segment, setSegment] = useState<Segment>('open');
  const [cancelTarget, setCancelTarget] = useState<HandoffItem | null>(null);
  const [reason, setReason] = useState('');

  const runCancel = async () => {
    if (!cancelTarget) return;
    const ok = await onCancel(cancelTarget.id, reason);
    if (ok) { setCancelTarget(null); setReason(''); }
  };

  if (loading) {
    return (
      <div className="flex-1 overflow-auto p-3" style={{ background: 'var(--app-bg)' }}>
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3 items-start">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="rounded-[var(--radius-xl)] p-4" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-card)' }}>
              <AppLoader size="sm" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  const isOpen = segment === 'open';

  return (
    <div className="flex-1 overflow-auto p-3" style={{ background: 'var(--app-bg)' }}>
      <style>{CONNECTOR_CSS}</style>
      {/* Sub-filter: open vs history — shared simple/pro segmented control */}
      <div className="mb-3">
        <SegmentedControl<Segment>
          value={segment}
          onChange={setSegment}
          options={[
            { value: 'open', label: t.dispatchDeskPage.handoffSegmentOpen ?? 'En cours', count: open.length },
            { value: 'history', label: t.dispatchDeskPage.handoffSegmentHistory ?? 'Historique', count: history.length },
          ]}
        />
      </div>

      {isOpen ? (
        open.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20">
            <div className="flex items-center justify-center rounded-full mb-3"
              style={{ width: 44, height: 44, background: 'var(--surface-sunken)', border: '1px solid var(--border)' }}>
              <IconPackageExport size={20} stroke={2} style={{ color: 'var(--text-soft)' }} />
            </div>
            <p className="text-sm font-[600]" style={{ color: 'var(--text-secondary)' }}>
              {t.dispatchDeskPage.handoffEmpty}
            </p>
          </div>
        ) : (
          <HandoffOpenTable
            items={open}
            t={t}
            isReadOnly={isReadOnly}
            cancellingId={cancellingId}
            onCancel={(item) => { setCancelTarget(item); setReason(''); }}
          />
        )
      ) : (
        history.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20">
            <div className="flex items-center justify-center rounded-full mb-3"
              style={{ width: 44, height: 44, background: 'var(--surface-sunken)', border: '1px solid var(--border)' }}>
              <IconPackageExport size={20} stroke={2} style={{ color: 'var(--text-soft)' }} />
            </div>
            <p className="text-sm font-[600]" style={{ color: 'var(--text-secondary)' }}>
              {t.dispatchDeskPage.handoffHistoryEmpty ?? 'No completed transfers'}
            </p>
          </div>
        ) : (
          <HandoffHistoryTable items={history} t={t} />
        )
      )}

      <ConfirmModal
        open={cancelTarget !== null}
        title={t.dispatchDeskPage.handoffCancelTitle}
        description={t.dispatchDeskPage.handoffCancelDescription}
        variant="danger"
        reasonLabel={t.dispatchDeskPage.handoffCancelReasonLabel}
        reason={reason}
        onReasonChange={setReason}
        confirmLabel={t.dispatchDeskPage.handoffCancelConfirm}
        cancelLabel={t.actions.cancel}
        loading={cancellingId === cancelTarget?.id}
        onConfirm={() => void runCancel()}
        onCancel={() => { setCancelTarget(null); setReason(''); }}
      />
    </div>
  );
}
