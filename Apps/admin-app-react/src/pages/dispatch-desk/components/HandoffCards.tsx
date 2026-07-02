import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  IconClock, IconX, IconChevronDown, IconMapPin, IconCheck,
  IconAlertTriangle, IconPackageExport, IconArrowNarrowRight,
} from '@tabler/icons-react';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { SegmentedControl } from '@/components/ui/SegmentedControl';
import { AppLoader } from '@/components/AppLoader';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
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
  t: any;
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

export function phaseOf(h: HandoffItem): Phase {
  if (h.state === 'CONFIRMED') return 'confirmed';
  if (h.state === 'EXPIRED') return 'expired';
  if (h.state === 'CANCELLED') return 'cancelled';
  if (isHandoffOverdue(h)) return 'overdue';
  if (h.state === 'IN_PROGRESS') return 'active';
  return 'wait';
}

export interface CardView {
  status: StatusValue;
  label: string;
  accent: string;
  phase: Phase;
}

export function cardView(h: HandoffItem, t: any): CardView {
  const c = t.dispatchDeskPage;
  const phase = phaseOf(h);
  switch (phase) {
    case 'confirmed': return { status: 'COMPLETED', label: c.handoffStateConfirmed, accent: 'var(--success)', phase };
    case 'expired':   return { status: 'PENDING' as StatusValue, label: c.handoffStateExpired, accent: 'var(--warning)', phase };
    case 'cancelled': return { status: 'CANCELLED', label: c.handoffStateCancelled, accent: 'var(--text-soft)', phase };
    case 'overdue':   return { status: 'FAILED', label: c.handoffStateOverdue, accent: 'var(--danger)', phase };
    case 'active':    return { status: 'IN_PROGRESS', label: c.handoffStateInProgress, accent: 'var(--brand)', phase };
    default:          return { status: 'REQUESTED' as StatusValue, label: c.handoffStateRequested, accent: 'var(--info)', phase };
  }
}

function fmtTs(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function fmtMinutes(mins: number): string {
  if (mins < 60) return `${mins} min`;
  return `${Math.floor(mins / 60)}h${String(mins % 60).padStart(2, '0')}`;
}

/** "Bouclé en X" for a confirmed transfer (confirmedAt − requestedAt). */
function durationLabel(h: HandoffItem, t: any): string | null {
  if (h.state !== 'CONFIRMED' || !h.requestedAt || !h.confirmedAt) return null;
  const mins = Math.max(0, Math.round((new Date(h.confirmedAt).getTime() - new Date(h.requestedAt).getTime()) / 60000));
  return (t.dispatchDeskPage.handoffDuration ?? 'Completed in {d}').replace('{d}', fmtMinutes(mins));
}

/**
 * The custody connector — the signature element of a handoff card. It reads left→right
 * as "sender hands the parcel to receiver", and the LINE itself encodes the live state:
 * a marching dashed line while the parcel is in motion (awaiting scan), a solid line once
 * received, a broken segment when it failed. A centred checkpoint chip carries the phase
 * icon. This replaces color-only meaning — the badge above still names the state in words.
 */
function CustodyTrail({ h, view }: { h: HandoffItem; view: CardView }) {
  const { accent, phase } = view;
  const solid = phase === 'confirmed' || phase === 'overdue';
  const animated = phase === 'active';
  const broken = phase === 'expired' || phase === 'cancelled';

  const Checkpoint = () => {
    let icon: React.ReactNode;
    if (phase === 'confirmed') icon = <IconCheck size={11} stroke={3} />;
    else if (phase === 'overdue' || phase === 'expired') icon = <IconAlertTriangle size={10} stroke={2.5} />;
    else if (phase === 'cancelled') icon = <IconX size={11} stroke={3} />;
    else if (phase === 'active') return <span className="is-live inline-block rounded-full" style={{ width: 7, height: 7, background: accent }} />;
    else icon = <IconArrowNarrowRight size={12} stroke={2.5} />;
    return <span style={{ color: accent, display: 'flex' }}>{icon}</span>;
  };

  return (
    <div className="flex items-center gap-2">
      <DriverNode driverId={h.fromDriverId} name={h.fromDriverName} role="from" />
      <div className="relative flex-1 flex items-center justify-center" style={{ minWidth: 40 }}>
        <span
          className={`ho-conn${animated ? ' ho-conn--march' : ''}${solid ? ' ho-conn--solid' : ''}${broken ? ' ho-conn--broken' : ''}`}
          style={{ ['--ho-c' as any]: accent }}
          aria-hidden
        />
        <span
          className="relative flex items-center justify-center rounded-full shrink-0"
          style={{ width: 18, height: 18, background: 'var(--surface)', border: `1.5px solid ${accent}` }}
        >
          <Checkpoint />
        </span>
      </div>
      <DriverNode driverId={h.toDriverId} name={h.toDriverName} role="to" />
    </div>
  );
}

function DriverNode({ driverId, name, role }: { driverId?: string; name?: string; role: 'from' | 'to' }) {
  const isTo = role === 'to';
  return (
    <div
      className="flex items-center gap-1.5 min-w-0"
      style={{ flex: '1 1 0', justifyContent: isTo ? 'flex-end' : 'flex-start' }}
    >
      {!isTo && <DriverAvatarById driverId={driverId} name={name} size={26} />}
      <span
        className="text-xs truncate"
        style={{
          maxWidth: 88,
          color: isTo ? 'var(--text-primary)' : 'var(--text-secondary)',
          fontWeight: isTo ? 700 : 500,
        }}
      >
        {name ?? '—'}
      </span>
      {isTo && <DriverAvatarById driverId={driverId} name={name} size={26} />}
    </div>
  );
}

/** Live countdown to the one-time code expiry (only while IN_PROGRESS). */
function Countdown({ to, t }: { to: string; t: any }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);
  const remMs = new Date(to).getTime() - now;
  if (remMs <= 0) {
    return <span style={{ color: 'var(--danger)' }}>{t.dispatchDeskPage.handoffCodeExpired ?? 'code expired'}</span>;
  }
  const totalSec = Math.floor(remMs / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  const txt = h > 0 ? `${h}h${String(m).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
  return <span>{(t.dispatchDeskPage.handoffExpiresIn ?? 'expires in {t}').replace('{t}', txt)}</span>;
}

type Step = { label: string; at?: string; by?: string; status: StatusValue };

/** The full lifecycle trail: Requested → Code ready → Accepted / Expired / Cancelled. */
function lifecycle(h: HandoffItem, t: any): Step[] {
  const c = t.dispatchDeskPage;
  const steps: Step[] = [
    { label: c.handoffStepRequested, at: h.requestedAt, by: h.requestedBy, status: 'REQUESTED' as StatusValue },
  ];
  if (h.inProgressAt) {
    steps.push({ label: c.handoffStepCodeReady, at: h.inProgressAt, status: 'IN_PROGRESS' });
  }
  if (h.state === 'CONFIRMED') {
    steps.push({ label: c.handoffStepConfirmed, at: h.confirmedAt, by: h.toDriverName, status: 'COMPLETED' });
  } else if (h.state === 'EXPIRED') {
    steps.push({ label: c.handoffStepExpired, at: h.expiredAt, status: 'SLA_BREACH' });
  } else if (h.state === 'CANCELLED') {
    steps.push({ label: c.handoffStepCancelled, at: h.cancelledAt, by: h.cancelledBy, status: 'CANCELLED' });
  } else {
    steps.push({ label: c.handoffStepPending, status: 'PENDING' as StatusValue });
  }
  return steps;
}

/** Vertical dot+line lifecycle trail — each step is a filled/hollow dot with a connecting
 *  rail, label, actor, and timestamp. No StatusBadge — the dot colour + text weight carry meaning. */
function LifecycleTrail({ h, t }: { h: HandoffItem; t: any }) {
  const steps = lifecycle(h, t);
  const by = t.dispatchDeskPage.handoffByLabel ?? 'by';

  function dotColor(s: Step): string {
    switch (s.status) {
      case 'COMPLETED': return 'var(--success)';
      case 'IN_PROGRESS': return 'var(--brand)';
      case 'REQUESTED': return 'var(--info)';
      case 'SLA_BREACH': return 'var(--danger)';
      case 'CANCELLED': return 'var(--text-soft)';
      default: return 'var(--border-strong)';
    }
  }

  return (
    <div className="px-3 pb-2.5 pt-2 border-t" style={{ background: 'var(--surface-sunken)', borderColor: 'var(--border)' }}>
      {steps.map((s, i) => {
        const reached = s.status !== ('PENDING' as StatusValue);
        return (
          <div key={i} className="flex items-start gap-2.5">
            <div className="flex flex-col items-center self-stretch">
              <span
                className="rounded-full shrink-0"
                style={{
                  width: 8, height: 8, marginTop: 4,
                  background: 'var(--surface)',
                  border: `1.5px solid ${reached ? dotColor(s) : 'var(--border-strong)'}`,
                }}
              />
              {i < steps.length - 1 && (
                <span className="flex-1 w-px my-0.5" style={{ background: 'var(--border)', minHeight: 12 }} />
              )}
            </div>
            <div className="flex flex-col pb-2.5 min-w-0">
              <span
                className="text-xs font-[600]"
                style={{ color: reached ? 'var(--text-primary)' : 'var(--text-soft)' }}
              >
                {s.label}
              </span>
              <span className="flex items-center gap-1.5 text-2xs" style={{ color: 'var(--text-muted)' }}>
                {s.by && <span>{by} {s.by}</span>}
                {s.by && s.at && <span>·</span>}
                {s.at && <span className="font-mono">{fmtTs(s.at)}</span>}
              </span>
            </div>
          </div>
        );
      })}
      {h.reason && (
        <div className="mt-0.5 flex gap-1.5 text-2xs pl-[18px]" style={{ color: 'var(--text-secondary)' }}>
          <span className="font-[700] shrink-0" style={{ color: 'var(--text-muted)' }}>
            {t.dispatchDeskPage.handoffReasonLabel ?? 'Reason'} ·
          </span>
          <span className="min-w-0">{h.reason}</span>
        </div>
      )}
    </div>
  );
}

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
function HandoffCard({ h, isOpenItem, isReadOnly, cancellingId, onCancelClick, t }: {
  h: HandoffItem;
  isOpenItem: boolean;
  isReadOnly: boolean;
  cancellingId: string | null;
  onCancelClick: (h: HandoffItem) => void;
  t: any;
}) {
  const [expanded, setExpanded] = useState(false);
  const view = cardView(h, t);
  const ref = h.erpOrderId || h.deliveryId?.slice(0, 8) || '—';
  const showCountdown = h.state === 'IN_PROGRESS' && !!h.tokenExpiresAt;
  const duration = durationLabel(h, t);
  const borderColor = view.phase === 'overdue' ? 'var(--danger)' : 'var(--border)';

  return (
    <article
      className="rounded-[var(--radius-xl)] overflow-hidden flex flex-col dispatch-card"
      style={{ background: 'var(--surface)', border: `1px solid ${borderColor}`, boxShadow: 'var(--shadow-card)' }}
    >
      <div className="p-4 flex flex-col gap-3">
        {/* Header: phase label + ref */}
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-[600]" style={{ color: view.accent }}>
            {view.label}
          </span>
          <Link
            to={h.deliveryId ? `/deliveries/${h.deliveryId}` : '/dispatch-desk'}
            className="font-mono text-2xs font-[600] hover:underline shrink-0"
            style={{ color: 'var(--brand)' }}
          >
            {ref}
          </Link>
        </div>

        {/* Client name — primary identity, large */}
        <p className="text-lg font-bold truncate leading-tight" style={{ color: 'var(--text-primary)' }}>
          {h.clientName ?? '—'}
        </p>

        {/* Custody connector: from → to */}
        <CustodyTrail h={h} view={view} />

        {/* Address */}
        {h.dropoffAddress && (
          <div className="flex items-center gap-1 min-w-0" style={{ color: 'var(--text-muted)' }}>
            <IconMapPin size={12} stroke={2} className="shrink-0" />
            <span className="text-xs truncate">{h.dropoffAddress}</span>
          </div>
        )}
      </div>

      {/* Expandable detail section */}
      {expanded && (
        <div className="border-t" style={{ borderColor: 'var(--border)' }}>
          {/* Compact info grid */}
          <div className="grid grid-cols-2 gap-x-4 gap-y-2 px-4 py-3" style={{ background: 'var(--surface-sunken)' }}>
            <div className="flex flex-col gap-0.5">
              <span className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>
                {t.dispatchDeskPage.handoffFromLabel ?? 'Envoyé par'}
              </span>
              <span className="text-xs font-[600] truncate" style={{ color: 'var(--text-primary)' }}>
                {h.fromDriverName ?? '—'}
              </span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>
                {t.dispatchDeskPage.handoffToLabel ?? 'Reçu par'}
              </span>
              <span className="text-xs font-[600] truncate" style={{ color: 'var(--text-primary)' }}>
                {h.toDriverName ?? '—'}
              </span>
            </div>
            <div className="flex flex-col gap-0.5">
              <span className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>
                {t.dispatchDeskPage.handoffRequestedAt ?? 'Demandé le'}
              </span>
              <span className="text-xs font-mono" style={{ color: 'var(--text-primary)' }}>
                {fmtTs(h.requestedAt)}
              </span>
            </div>
            {duration && (
              <div className="flex flex-col gap-0.5">
                <span className="text-2xs font-[600]" style={{ color: 'var(--text-muted)' }}>
                  {t.dispatchDeskPage.handoffDurationLabel ?? 'Durée'}
                </span>
                <span className="text-xs font-mono" style={{ color: 'var(--text-primary)' }}>
                  {duration}
                </span>
              </div>
            )}
          </div>
          {/* Lifecycle trail */}
          <LifecycleTrail h={h} t={t} />
        </div>
      )}

      {/* Footer: time + actions */}
      <div className="flex items-center justify-between px-4 py-2.5 mt-auto border-t" style={{ borderColor: 'var(--border)' }}>
        <div className="flex items-center gap-1.5 text-2xs font-mono" style={{ color: showCountdown ? 'var(--brand)' : 'var(--text-muted)' }}>
          {showCountdown
            ? <span className="is-live inline-block rounded-full" style={{ width: 6, height: 6, background: 'var(--brand)' }} />
            : <IconClock size={12} stroke={2} />}
          {showCountdown
            ? <Countdown to={h.tokenExpiresAt!} t={t} />
            : duration
              ? duration
              : fmtTs(isOpenItem ? h.requestedAt : (h.confirmedAt ?? h.cancelledAt ?? h.expiredAt ?? h.requestedAt))}
        </div>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => setExpanded(v => !v)}
            className="text-2xs font-[600] h-6 px-2 rounded-[var(--radius)] flex items-center gap-1 transition-colors hover:bg-[var(--hover-bg)]"
            style={{ color: 'var(--text-muted)' }}
            aria-expanded={expanded}
          >
            {t.dispatchDeskPage.handoffDetailsButton ?? 'Détails'}
            <IconChevronDown size={12} stroke={2.5} style={{ transform: expanded ? 'rotate(180deg)' : 'none', transition: 'transform 0.15s' }} />
          </button>
          {isOpenItem && !isReadOnly && (
            <button
              type="button"
              onClick={() => onCancelClick(h)}
              disabled={cancellingId === h.id}
              className="text-2xs font-[600] h-6 px-2.5 rounded-[var(--radius)] flex items-center gap-1 transition-colors disabled:opacity-50 hover:bg-[var(--danger-bg)]"
              style={{ color: 'var(--danger)' }}
            >
              <IconX size={12} stroke={2.5} />
              {t.dispatchDeskPage.handoffCancelButton}
            </button>
          )}
        </div>
      </div>
    </article>
  );
}

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
              {t.dispatchDeskPage.handoffHistoryEmpty ?? 'Aucun transfert terminé'}
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
