import { Link } from 'react-router-dom';
import type { TranslationSchema } from '@/lib/i18n/LocaleContext';
import { tlabel } from '@/lib/i18n/i18n-dict';
import {
  IconCheck, IconX, IconAlertTriangle, IconArrowNarrowRight,
  IconClock, IconMapPin, IconRoute,
} from '@tabler/icons-react';
import { AppModal } from '@/components/overlays/AppModal';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import type { HandoffItem } from '../types';
import type { Phase } from './HandoffCards';

interface Props {
  h: HandoffItem | null;
  open: boolean;
  onClose: () => void;
  phase: Phase;
  accent: string;
  t: TranslationSchema;
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

type Step = { label: string; at?: string; by?: string; reached: boolean; dotColor: string };

function lifecycleSteps(h: HandoffItem, t: TranslationSchema): Step[] {
  const c = t.dispatchDeskPage;
  const steps: Step[] = [
    { label: c.handoffStepRequested, at: h.requestedAt, by: h.requestedBy, reached: true, dotColor: 'var(--info)' },
  ];
  if (h.inProgressAt) {
    steps.push({ label: c.handoffStepCodeReady, at: h.inProgressAt, reached: true, dotColor: 'var(--brand)' });
  }
  if (h.state === 'CONFIRMED') {
    steps.push({ label: c.handoffStepConfirmed, at: h.confirmedAt, by: h.toDriverName, reached: true, dotColor: 'var(--success)' });
  } else if (h.state === 'EXPIRED') {
    steps.push({ label: c.handoffStepExpired, at: h.expiredAt, reached: true, dotColor: 'var(--danger)' });
  } else if (h.state === 'CANCELLED') {
    steps.push({ label: c.handoffStepCancelled, at: h.cancelledAt, by: h.cancelledBy, reached: true, dotColor: 'var(--text-soft)' });
  } else {
    steps.push({ label: c.handoffStepPending, reached: false, dotColor: 'var(--border-strong)' });
  }
  return steps;
}

/** Large-format custody connector for the modal — sender and receiver with avatars,
 *  the line encodes the terminal state. No animation (read-only). */
function CustodyVisual({ h, accent, phase, t }: { h: HandoffItem; accent: string; phase: Phase; t: TranslationSchema }) {
  const solid = phase === 'confirmed' || phase === 'overdue';
  const broken = phase === 'expired' || phase === 'cancelled';

  const Checkpoint = () => {
    let icon: React.ReactNode;
    if (phase === 'confirmed') icon = <IconCheck size={13} stroke={3} />;
    else if (phase === 'overdue' || phase === 'expired') icon = <IconAlertTriangle size={12} stroke={2.5} />;
    else if (phase === 'cancelled') icon = <IconX size={13} stroke={3} />;
    else icon = <IconArrowNarrowRight size={14} stroke={2.5} />;
    return <span style={{ color: accent, display: 'flex' }}>{icon}</span>;
  };

  return (
    <div className="flex items-center gap-4 py-4">
      {/* Sender */}
      <div className="flex items-center gap-2 min-w-0 flex-1 justify-end">
        <div className="min-w-0 text-right">
          <p className="text-xs font-[600] truncate" style={{ color: 'var(--text-primary)' }}>{h.fromDriverName ?? '—'}</p>
          <p className="text-2xs" style={{ color: 'var(--text-muted)' }}>{tlabel(t.dispatchDeskPage, 'handoffFromLabel') ?? 'Sent by'}</p>
        </div>
        <DriverAvatarById driverId={h.fromDriverId} name={h.fromDriverName} size={32} />
      </div>

      {/* Connector line */}
      <div className="relative flex items-center justify-center" style={{ minWidth: 56 }}>
        <span
          className={`ho-conn${solid ? ' ho-conn--solid' : ''}${broken ? ' ho-conn--broken' : ''}`}
          style={{ '--ho-c': accent } as React.CSSProperties}
          aria-hidden
        />
        <span
          className="relative flex items-center justify-center rounded-full shrink-0"
          style={{ width: 24, height: 24, background: 'var(--surface)', border: `2px solid ${accent}` }}
        >
          <Checkpoint />
        </span>
      </div>

      {/* Receiver */}
      <div className="flex items-center gap-2 min-w-0 flex-1">
        <DriverAvatarById driverId={h.toDriverId} name={h.toDriverName} size={32} />
        <div className="min-w-0">
          <p className="text-xs font-[600] truncate" style={{ color: 'var(--text-primary)' }}>{h.toDriverName ?? '—'}</p>
          <p className="text-2xs" style={{ color: 'var(--text-muted)' }}>{tlabel(t.dispatchDeskPage, 'handoffToLabel') ?? 'Received by'}</p>
        </div>
      </div>
    </div>
  );
}

export function HandoffDetailModal({ h, open, onClose, phase, accent, t }: Props) {
  if (!h) return null;

  const ref = h.erpOrderId || h.deliveryId?.slice(0, 8) || '—';
  const steps = lifecycleSteps(h, t);
  const byLabel = t.dispatchDeskPage.handoffByLabel ?? 'by';

  const requestedMs = h.requestedAt ? new Date(h.requestedAt).getTime() : 0;
  const endedMs = h.confirmedAt
    ? new Date(h.confirmedAt).getTime()
    : h.cancelledAt
      ? new Date(h.cancelledAt).getTime()
      : h.expiredAt
        ? new Date(h.expiredAt).getTime()
        : 0;
  const durationMs = requestedMs && endedMs ? endedMs - requestedMs : 0;
  const duration = durationMs > 0 ? fmtMinutes(Math.round(durationMs / 60000)) : null;

  const statusLabel = {
    confirmed: t.dispatchDeskPage.handoffStateConfirmed,
    expired: t.dispatchDeskPage.handoffStateExpired,
    cancelled: t.dispatchDeskPage.handoffStateCancelled,
    overdue: t.dispatchDeskPage.handoffStateOverdue,
    active: t.dispatchDeskPage.handoffStateInProgress,
    wait: t.dispatchDeskPage.handoffStateRequested,
  }[phase];

  return (
    <AppModal
      open={open}
      onClose={onClose}
      title={
        <div className="flex items-center gap-2">
          <span className="text-sm font-bold truncate" style={{ color: 'var(--text-primary)' }}>
            {h.clientName ?? '—'}
          </span>
          <span className="text-xs font-[600]" style={{ color: accent }}>
            {statusLabel}
          </span>
        </div>
      }
      subtitle={`${tlabel(t.dispatchDeskPage, 'handoffSubtitle') ?? 'Transfer'} · ${ref}`}
      size="lg"
    >
      <div className="flex flex-col">
        {/* Custody visual — large format */}
        <CustodyVisual h={h} accent={accent} phase={phase} t={t} />

        {/* Address */}
        {h.dropoffAddress && (
          <div className="flex items-center gap-1.5 px-1 pb-3" style={{ color: 'var(--text-muted)' }}>
            <IconMapPin size={12} stroke={2} className="shrink-0" />
            <span className="text-xs">{h.dropoffAddress}</span>
          </div>
        )}

        {/* Routes */}
        {(h.routeId || h.toRouteId) && (
          <div className="flex items-center gap-4 px-1 pb-3" style={{ color: 'var(--text-muted)' }}>
            {h.routeId && (
              <div className="flex items-center gap-1.5 min-w-0">
                <IconRoute size={12} stroke={2} className="shrink-0" />
                <Link
                  to={`/routes/${h.routeId}`}
                  onClick={(e) => e.stopPropagation()}
                  className="text-xs font-[500] hover:underline truncate"
                  style={{ color: 'var(--brand)' }}
                >
                  {h.routeName ?? h.routeId.slice(0, 8)}
                </Link>
              </div>
            )}
            {h.routeId && h.toRouteId && (
              <IconArrowNarrowRight size={12} stroke={2} className="shrink-0" style={{ color: 'var(--text-soft)' }} />
            )}
            {h.toRouteId && (
              <div className="flex items-center gap-1.5 min-w-0">
                <IconRoute size={12} stroke={2} className="shrink-0" />
                <Link
                  to={`/routes/${h.toRouteId}`}
                  onClick={(e) => e.stopPropagation()}
                  className="text-xs font-[500] hover:underline truncate"
                  style={{ color: 'var(--brand)' }}
                >
                  {h.toRouteName ?? h.toRouteId.slice(0, 8)}
                </Link>
              </div>
            )}
          </div>
        )}

        {/* Duration */}
        {duration && (
          <div className="flex items-center gap-1.5 px-1 pb-3 text-xs" style={{ color: 'var(--text-muted)' }}>
            <IconClock size={12} stroke={2} />
            <span className="font-mono">{duration}</span>
          </div>
        )}

        {/* Lifecycle timeline */}
        <div className="border-t pt-3" style={{ borderColor: 'var(--border)' }}>
          <p className="text-xs font-[700] mb-2" style={{ color: 'var(--text-primary)' }}>
            {tlabel(t.dispatchDeskPage, 'handoffLifecycle') ?? 'Timeline'}
          </p>
          {steps.map((s, i) => (
            <div key={i} className="flex items-start gap-2.5">
              <div className="flex flex-col items-center self-stretch">
                <span
                  className="rounded-full shrink-0"
                  style={{
                    width: 8, height: 8, marginTop: 4,
                    background: 'var(--surface)',
                    border: `1.5px solid ${s.reached ? s.dotColor : 'var(--border-strong)'}`,
                  }}
                />
                {i < steps.length - 1 && (
                  <span className="flex-1 w-px my-0.5" style={{ background: 'var(--border)', minHeight: 12 }} />
                )}
              </div>
              <div className="flex flex-col pb-2.5 min-w-0">
                <span
                  className="text-xs font-[600]"
                  style={{ color: s.reached ? 'var(--text-primary)' : 'var(--text-soft)' }}
                >
                  {s.label}
                </span>
                {(s.at || s.by) && (
                  <span className="text-2xs" style={{ color: 'var(--text-muted)' }}>
                    {s.at && <span className="font-mono">{fmtTs(s.at)}</span>}
                    {s.by && <span>{s.at ? ' · ' : ''}{byLabel} {s.by}</span>}
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>

        {/* Reason */}
        {h.reason && (
          <div className="border-t pt-3 mt-1" style={{ borderColor: 'var(--border)' }}>
            <p className="text-2xs font-[600] mb-1" style={{ color: 'var(--text-muted)' }}>
              {t.dispatchDeskPage.handoffReasonLabel ?? 'Reason'}
            </p>
            <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>{h.reason}</p>
          </div>
        )}
      </div>
    </AppModal>
  );
}
