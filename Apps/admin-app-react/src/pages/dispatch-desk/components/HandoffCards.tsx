import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { IconArrowRight, IconClock, IconArrowsExchange, IconCheck } from '@tabler/icons-react';
import { ConfirmModal } from '@/components/overlays/ConfirmModal';
import { AppLoader } from '@/components/AppLoader';
import { formatElapsed } from '../formatters';
import { isHandoffOverdue } from '../hooks/useHandoffs';
import type { HandoffItem } from '../types';

interface Props {
  open: HandoffItem[];
  loading: boolean;
  isReadOnly: boolean;
  cancellingId: string | null;
  onCancel: (id: string, reason: string) => Promise<boolean>;
  t: any;
}

/** Urgency styling for the state chip + card accent (pastel, StatusBadge-aligned). */
function chipStyle(h: HandoffItem, t: any): { label: string; color: string; bg: string; accent: string } {
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

function DriverChip({ name }: { name?: string }) {
  return (
    <div className="flex items-center gap-1.5 min-w-0">
      <div
        className="flex items-center justify-center rounded-full shrink-0 text-[10px] font-[600]"
        style={{ width: 24, height: 24, background: 'var(--hover-bg)', color: 'var(--text-secondary)' }}
      >
        {initials(name)}
      </div>
      <span className="text-[11px] font-[500] truncate" style={{ maxWidth: 92, color: 'var(--text-primary)' }}>
        {name ?? '—'}
      </span>
    </div>
  );
}

export function HandoffCards({ open, loading, isReadOnly, cancellingId, onCancel, t }: Props) {
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

  if (open.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center" style={{ background: 'var(--app-bg)' }}>
        <IconCheck size={22} style={{ color: 'var(--text-soft)', marginBottom: 6 }} />
        <p className="text-[12px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.handoffEmpty}</p>
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-auto p-3" style={{ background: 'var(--app-bg)' }}>
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
        {open.map(h => {
          const chip = chipStyle(h, t);
          const ref = h.erpOrderId || h.deliveryId?.slice(0, 8) || '—';
          return (
            <article
              key={h.id}
              className="rounded-[var(--radius)] border overflow-hidden flex flex-col"
              style={{ borderColor: 'var(--border)', background: 'var(--surface)', borderLeft: `3px solid ${chip.accent}` }}
            >
              <div className="p-3 flex flex-col gap-2.5">
                {/* Header: ref + state chip */}
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <Link
                      to={h.deliveryId ? `/deliveries/${h.deliveryId}` : '/dispatch-desk'}
                      className="font-mono text-[11px] font-[600] hover:underline"
                      style={{ color: 'var(--brand)' }}
                    >
                      {ref}
                    </Link>
                    <p className="text-[12px] font-[600] truncate mt-0.5" style={{ color: 'var(--text-primary)' }}>
                      {h.clientName ?? '—'}
                    </p>
                  </div>
                  <span
                    className="text-[10px] font-[600] px-1.5 py-0.5 rounded shrink-0"
                    style={{ color: chip.color, background: chip.bg }}
                  >
                    {chip.label}
                  </span>
                </div>

                {/* Driver flow: from → to */}
                <div
                  className="flex items-center gap-2 rounded px-2 py-1.5"
                  style={{ background: 'var(--app-bg)' }}
                >
                  <DriverChip name={h.fromDriverName} />
                  <IconArrowRight size={14} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                  <DriverChip name={h.toDriverName} />
                </div>

                {/* Address */}
                {h.dropoffAddress && (
                  <p className="text-[11px] truncate" style={{ color: 'var(--text-muted)' }}>{h.dropoffAddress}</p>
                )}
              </div>

              {/* Footer: age + cancel */}
              <div
                className="flex items-center justify-between px-3 py-2 mt-auto border-t"
                style={{ borderColor: 'var(--border)' }}
              >
                <div className="flex items-center gap-1 text-[10px] font-mono" style={{ color: 'var(--text-muted)' }}>
                  <IconClock size={12} />
                  {formatElapsed(h.requestedAt, t)}
                </div>
                {!isReadOnly && (
                  <button
                    type="button"
                    onClick={() => { setCancelTarget(h); setReason(''); }}
                    disabled={cancellingId === h.id}
                    className="text-[11px] font-[500] h-6 px-2.5 rounded-[var(--radius)] border flex items-center gap-1 transition-colors hover:bg-[var(--hover-bg)] disabled:opacity-50"
                    style={{ borderColor: 'var(--border)', color: 'var(--danger)' }}
                  >
                    <IconArrowsExchange size={12} />
                    {t.dispatchDeskPage.handoffCancelButton}
                  </button>
                )}
              </div>
            </article>
          );
        })}
      </div>

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
