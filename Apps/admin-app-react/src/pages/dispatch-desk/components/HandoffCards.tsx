import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  IconArrowRight, IconClock, IconArrowsExchange, IconCheck, IconChevronDown,
} from '@tabler/icons-react';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { AppLoader } from '@/components/AppLoader';
import { isHandoffOverdue } from '../hooks/useHandoffs';
import type { HandoffItem } from '../types';

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

/** Urgency / outcome styling for the state chip + card accent. */
function chipStyle(h: HandoffItem, t: any): { label: string; color: string; bg: string; accent: string } {
  switch (h.state) {
    case 'CONFIRMED':
      return { label: t.dispatchDeskPage.handoffStateConfirmed, color: 'var(--success)', bg: 'var(--success-bg)', accent: 'var(--success)' };
    case 'EXPIRED':
      return { label: t.dispatchDeskPage.handoffStateExpired, color: 'var(--warning)', bg: 'var(--warning-bg)', accent: 'var(--warning)' };
    case 'CANCELLED':
      return { label: t.dispatchDeskPage.handoffStateCancelled, color: 'var(--text-muted)', bg: 'var(--hover-bg)', accent: 'var(--text-soft)' };
  }
  if (isHandoffOverdue(h)) {
    return { label: t.dispatchDeskPage.handoffStateOverdue, color: 'var(--danger)', bg: 'var(--danger-bg)', accent: 'var(--danger)' };
  }
  if (h.state === 'IN_PROGRESS') {
    return { label: t.dispatchDeskPage.handoffStateInProgress, color: 'var(--brand)', bg: 'var(--brand-soft)', accent: 'var(--brand)' };
  }
  return { label: t.dispatchDeskPage.handoffStateRequested, color: 'var(--info)', bg: 'var(--info-bg)', accent: 'var(--info)' };
}

function initials(name?: string): string {
  if (!name) return '—';
  const parts = name.trim().split(/\s+/).slice(0, 2);
  return parts.map(p => p[0]?.toUpperCase() ?? '').join('') || '—';
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

function DriverChip({ name }: { name?: string }) {
  return (
    <div className="flex items-center gap-1.5 min-w-0">
      <div
        className="flex items-center justify-center rounded-full shrink-0 text-2xs font-[600]"
        style={{ width: 24, height: 24, background: 'var(--hover-bg)', color: 'var(--text-secondary)' }}
      >
        {initials(name)}
      </div>
      <span className="text-xs font-[500] truncate" style={{ maxWidth: 92, color: 'var(--text-primary)' }}>
        {name ?? '—'}
      </span>
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

type Step = { label: string; at?: string; by?: string; dotColor: string };

/** The full lifecycle trail: Requested → Code ready → Accepted / Expired / Cancelled. */
function lifecycle(h: HandoffItem, t: any): Step[] {
  const c = t.dispatchDeskPage;
  const steps: Step[] = [
    { label: c.handoffStepRequested, at: h.requestedAt, by: h.requestedBy, dotColor: 'var(--text-soft)' },
  ];
  if (h.inProgressAt) {
    steps.push({ label: c.handoffStepCodeReady, at: h.inProgressAt, dotColor: 'var(--brand)' });
  }
  if (h.state === 'CONFIRMED') {
    steps.push({ label: c.handoffStepConfirmed, at: h.confirmedAt, by: h.toDriverName, dotColor: 'var(--success)' });
  } else if (h.state === 'EXPIRED') {
    steps.push({ label: c.handoffStepExpired, at: h.expiredAt, dotColor: 'var(--warning)' });
  } else if (h.state === 'CANCELLED') {
    steps.push({ label: c.handoffStepCancelled, at: h.cancelledAt, by: h.cancelledBy, dotColor: 'var(--danger)' });
  } else {
    steps.push({ label: c.handoffStepPending, dotColor: 'var(--border)' });
  }
  return steps;
}

function LifecycleTrail({ h, t }: { h: HandoffItem; t: any }) {
  const steps = lifecycle(h, t);
  const by = t.dispatchDeskPage.handoffByLabel ?? 'by';
  return (
    <div className="flex flex-col gap-0 px-3 pb-3 pt-1">
      {steps.map((s, i) => (
        <div key={i} className="flex items-start gap-2">
          <div className="flex flex-col items-center self-stretch">
            <span className="rounded-full shrink-0" style={{ width: 8, height: 8, marginTop: 5, background: s.dotColor }} />
            {i < steps.length - 1 && <span className="flex-1 w-px my-0.5" style={{ background: 'var(--border)', minHeight: 14 }} />}
          </div>
          <div className="flex flex-col pb-2 min-w-0">
            <span className="text-2xs font-[600]" style={{ color: 'var(--text-primary)' }}>{s.label}</span>
            <span className="text-2xs font-mono" style={{ color: 'var(--text-muted)' }}>
              {fmtTs(s.at)}{s.by ? ` · ${by} ${s.by}` : ''}
            </span>
          </div>
        </div>
      ))}
      {h.reason && (
        <div className="mt-1 text-2xs" style={{ color: 'var(--text-muted)' }}>
          <span className="font-[600]">{t.dispatchDeskPage.handoffReasonLabel ?? 'Reason'}: </span>{h.reason}
        </div>
      )}
    </div>
  );
}

export function HandoffCards({ open, history, loading, isReadOnly, cancellingId, onCancel, t }: Props) {
  const [segment, setSegment] = useState<Segment>('open');
  const [expandedId, setExpandedId] = useState<string | null>(null);
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
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="rounded-[var(--radius)] border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
              <AppLoader size="sm" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  const list = segment === 'open' ? open : history;

  const SegmentTab = ({ id, label, count }: { id: Segment; label: string; count: number }) => {
    const active = segment === id;
    return (
      <button
        type="button"
        onClick={() => { setSegment(id); setExpandedId(null); }}
        className="text-xs font-[600] px-3 py-1.5 rounded-[var(--radius)] transition-colors"
        style={{
          background: active ? 'var(--brand)' : 'transparent',
          color: active ? '#fff' : 'var(--text-muted)',
        }}
      >
        {label} <span style={{ opacity: 0.8 }}>· {count}</span>
      </button>
    );
  };

  return (
    <div className="flex-1 overflow-auto p-3" style={{ background: 'var(--app-bg)' }}>
      {/* Segmented control: open vs history */}
      <div className="flex items-center gap-1 mb-3 p-1 rounded-[var(--radius)] w-fit" style={{ background: 'var(--surface)', border: '1px solid var(--border)' }}>
        <SegmentTab id="open" label={t.dispatchDeskPage.handoffSegmentOpen ?? 'En cours'} count={open.length} />
        <SegmentTab id="history" label={t.dispatchDeskPage.handoffSegmentHistory ?? 'Historique'} count={history.length} />
      </div>

      {list.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16">
          <IconCheck size={22} stroke={2.5} style={{ color: 'var(--text-soft)', marginBottom: 6 }} />
          <p className="text-sm font-[500]" style={{ color: 'var(--text-muted)' }}>
            {segment === 'open' ? t.dispatchDeskPage.handoffEmpty : (t.dispatchDeskPage.handoffHistoryEmpty ?? 'Aucun transfert terminé')}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          {list.map(h => {
            const chip = chipStyle(h, t);
            const ref = h.erpOrderId || h.deliveryId?.slice(0, 8) || '—';
            const expanded = expandedId === h.id;
            const isOpenItem = segment === 'open';
            const showCountdown = h.state === 'IN_PROGRESS' && !!h.tokenExpiresAt;
            const duration = durationLabel(h, t);
            return (
              <article
                key={h.id}
                className="rounded-[var(--radius)] overflow-hidden flex flex-col dispatch-card"
                style={{ background: 'var(--surface)', borderLeft: `3px solid ${chip.accent}`, boxShadow: 'var(--shadow-card)' }}
              >
                <div className="p-3 flex flex-col gap-2.5">
                  {/* Header: ref + state chip */}
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <Link
                        to={h.deliveryId ? `/deliveries/${h.deliveryId}` : '/dispatch-desk'}
                        className="font-mono text-xs font-[600] hover:underline"
                        style={{ color: 'var(--brand)' }}
                      >
                        {ref}
                      </Link>
                      <p className="text-sm font-[600] truncate mt-0.5" style={{ color: 'var(--text-primary)' }}>
                        {h.clientName ?? '—'}
                      </p>
                    </div>
                    <span
                      className="text-2xs font-[600] px-1.5 py-0.5 rounded shrink-0"
                      style={{ color: chip.color, background: chip.bg }}
                    >
                      {chip.label}
                    </span>
                  </div>

                  {/* Driver flow: from → to */}
                  <div className="flex items-center gap-2 rounded px-2 py-1.5" style={{ background: 'var(--app-bg)' }}>
                    <DriverChip name={h.fromDriverName} />
                    <IconArrowRight size={14} stroke={2.5} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                    <DriverChip name={h.toDriverName} />
                  </div>

                  {/* Address */}
                  {h.dropoffAddress && (
                    <p className="text-xs truncate" style={{ color: 'var(--text-muted)' }}>{h.dropoffAddress}</p>
                  )}
                </div>

                {/* Expandable lifecycle trail */}
                {expanded && <LifecycleTrail h={h} t={t} />}

                {/* Footer */}
                <div className="flex items-center justify-between px-3 py-2 mt-auto border-t" style={{ borderColor: 'var(--border)' }}>
                  <div className="flex items-center gap-1 text-2xs font-mono" style={{ color: showCountdown ? 'var(--brand)' : 'var(--text-muted)' }}>
                    <IconClock size={12} stroke={2.5} />
                    {showCountdown
                      ? <Countdown to={h.tokenExpiresAt!} t={t} />
                      : duration
                        ? duration
                        : fmtTs(isOpenItem ? h.requestedAt : (h.confirmedAt ?? h.cancelledAt ?? h.expiredAt ?? h.requestedAt))}
                  </div>
                  <div className="flex items-center gap-1.5">
                    <button
                      type="button"
                      onClick={() => setExpandedId(expanded ? null : h.id)}
                      className="text-2xs font-[500] h-6 px-2 rounded-[var(--radius)] flex items-center gap-1 transition-colors hover:bg-[var(--hover-bg)]"
                      style={{ color: 'var(--text-muted)' }}
                    >
                      {t.dispatchDeskPage.handoffDetailsButton ?? 'Détails'}
                      <IconChevronDown size={12} stroke={2.5} style={{ transform: expanded ? 'rotate(180deg)' : 'none', transition: 'transform 0.15s' }} />
                    </button>
                    {isOpenItem && !isReadOnly && (
                      <button
                        type="button"
                        onClick={() => { setCancelTarget(h); setReason(''); }}
                        disabled={cancellingId === h.id}
                        className="text-xs font-[500] h-6 px-2.5 rounded-[var(--radius)] border flex items-center gap-1 transition-colors hover:bg-[var(--hover-bg)] disabled:opacity-50"
                        style={{ borderColor: 'var(--border)', color: 'var(--danger)' }}
                      >
                        <IconArrowsExchange size={12} stroke={2.5} />
                        {t.dispatchDeskPage.handoffCancelButton}
                      </button>
                    )}
                  </div>
                </div>
              </article>
            );
          })}
        </div>
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
