import React from 'react';
import { Link } from 'react-router-dom';
import { IconArrowBack, IconCalendar, IconClock, IconMapPin, IconInbox } from '@tabler/icons-react';
import { IconAssign, IconReassign, IconReplan, IconCall } from '@/components/icons/DispatchIcons';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import StatusBadge from '@/components/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import SlaTimeline from '@/components/data-display/SlaTimeline';
import { FailureInfo } from '@/components/data-display/FailureInfo';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import {
  REASSIGNABLE_STATUSES, REPLANNABLE_STATUSES, STATUS_DOT, getDriverStatusTip,
} from '../constants';
import {
  formatElapsed, formatShortDate,
  needsClientContact, needsDriverContact, needsReturnToDepot,
} from '../formatters';
import { rowId } from '../utils';
import type { OpsException } from '../types';
import { formatMoney } from '@/lib/utils';

export function QueueDetail() {
  const {
    t, isReadOnly, drivers, selectedQueueRow,
    setDrawerTargets, openActionModal, setReturnTarget,
  } = useDispatchDeskContext();

  if (!selectedQueueRow) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-2 px-6 text-center">
        <IconInbox size={28} stroke={1.5} style={{ color: 'var(--text-soft)' }} />
        <p className="text-sm font-[500]" style={{ color: 'var(--text-muted)' }}>
          {t.dispatchDeskPage.queueSelectPrompt}
        </p>
      </div>
    );
  }

  const { delivery: d, alert } = selectedQueueRow;
  const id = rowId(d);
  const driver = drivers.find(dr => dr.id === d.driverId);
  const motif = (alert?.motif ?? '').toUpperCase().trim();

  const canReassign = (REASSIGNABLE_STATUSES as string[]).includes(d.status);
  const canReplan   = (REPLANNABLE_STATUSES as string[]).includes(d.status) && !canReassign;

  const target = {
    deliveryId: id, orderRef: d.orderRef, clientName: d.clientName,
    city: d.dropoffCity, status: d.status, driverName: d.driverName,
    routeId: d.routeId, routeName: d.routeName,
  };

  const slot = d.timeSlotStartTime && d.timeSlotEndTime
    ? `${d.timeSlotStartTime.slice(0, 5)}–${d.timeSlotEndTime.slice(0, 5)}`
    : d.timeSlotName || (d.requestedDeliveryDate ? d.requestedDeliveryDate.slice(0, 10) : null);
  const amount = typeof d.totalAmount === 'number' && d.totalAmount > 0
    ? formatMoney(d.totalAmount, d.currency ?? 'TND')
    : null;

  const exceptionFromDelivery = (): OpsException => ({
    deliveryId: id, orderId: d.orderId, orderRef: d.orderRef,
    routeId: d.routeId, routeName: d.routeName, status: d.status,
    motif: alert?.motif ?? d.failureReason ?? d.status, driverId: d.driverId, driverName: d.driverName,
    clientName: d.clientName, city: d.dropoffCity, zoneName: d.zoneName,
    severity: alert?.severity ?? 'WARNING', comment: alert?.comment ?? d.failureReason,
    createdAt: d.createdAt, updatedAt: d.updatedAt, scheduledAt: d.scheduledAt,
  });

  return (
    <div className="flex-1 flex flex-col min-h-0">
      {/* Two-column cockpit. LEFT = order/client/driver context (what & who).
          RIGHT = the full activity timeline (what happened & why) — fills the
          space a single 680px column used to leave empty on wide screens.
          Stacks to one column below xl so it stays usable on laptops/tablets. */}
      <ScrollArea className="flex-1 min-h-0">
        <div className="flex flex-col xl:flex-row xl:items-stretch gap-5 xl:gap-6 p-5">

          {/* ── LEFT: context ──────────────────────────────────────────────── */}
          <div className="flex flex-col gap-5 min-w-0 xl:flex-1 xl:max-w-[620px]">
          {/* Header — client hero with avatar + grouped status/health */}
          <div className="flex items-start justify-between gap-3">
            <div className="flex items-center gap-3 min-w-0">
              <div
                className="w-10 h-10 rounded-full flex items-center justify-center text-lg font-bold shrink-0"
                style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}
              >
                {(d.clientName ?? '?').slice(0, 1).toUpperCase()}
              </div>
              <div className="min-w-0">
                <p className="text-xl font-bold truncate leading-tight" style={{ color: 'var(--text-primary)' }}>
                  {d.clientName ?? '—'}
                </p>
                <Link to={`/deliveries/${id}`} className="font-mono text-xs font-[600] hover:underline" style={{ color: 'var(--brand)' }}>
                  {d.orderRef ?? d.erpOrderId ?? id.slice(0, 8)}
                </Link>
              </div>
            </div>
            <div className="flex flex-col items-end gap-1.5 shrink-0">
              <div className="flex items-center gap-1.5">
                <StatusBadge status={d.status} size="sm" />
                <SlaHealthBadge health={d.slaHealth ?? alert?.slaHealth} size="md" />
              </div>
              <Link to={`/deliveries/${id}`} className="text-xs font-[500] hover:underline" style={{ color: 'var(--text-secondary)' }}>
                {t.dispatchDeskPage.openLink}
              </Link>
            </div>
          </div>

          {/* Failure motif (motif d'échec) — same as the per-delivery page. Backend summary uses
              failureCode/failReason (not failureReason); fall back to the alert's fields. */}
          {(d.status === 'FAILED' || d.status === 'PARTIALLY_DELIVERED') &&
            (alert?.failureCode || (d as any).failureCode || alert?.comment || (d as any).failReason) && (
            <FailureInfo
              code={alert?.failureCode ?? (d as any).failureCode}
              reason={alert?.comment ?? (d as any).failReason}
            />
          )}

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {/* Driver */}
            <div className="rounded-[var(--radius)] p-3 flex flex-col gap-1.5 border" style={{ background: 'var(--surface)', borderColor: 'var(--border)' }}>
              <span className="text-2xs font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                {t.dispatchDeskPage.driverLabel}
              </span>
              {d.driverName ? (
                <div className="flex items-center gap-2">
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                  <div className="min-w-0">
                    <p className="text-base font-[600]" style={{ color: 'var(--text-primary)' }}>{d.driverName}</p>
                    {(d.driverPhone || driver?.phone) && (
                      <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{d.driverPhone ?? driver?.phone}</p>
                    )}
                  </div>
                  <span className="ms-auto text-2xs font-[500] shrink-0" style={{ color: 'var(--text-muted)' }}>
                    {getDriverStatusTip(driver?.onlineStatus, t)}
                  </span>
                </div>
              ) : (
                <span className="text-sm font-[500]" style={{ color: 'var(--text-muted)' }}>
                  {t.dispatchDeskPage.unassignedLabel}
                </span>
              )}
            </div>

            {/* Address / client */}
            <div className="rounded-[var(--radius)] p-3 flex flex-col gap-1.5 border" style={{ background: 'var(--surface)', borderColor: 'var(--border)' }}>
              <span className="text-2xs font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                {t.dispatchDeskPage.orderLabel}
              </span>
              {(d.dropoffAddress || d.dropoffCity || d.zoneName) ? (
                <div className="flex items-start gap-1.5" style={{ color: 'var(--text-secondary)' }}>
                  <IconMapPin size={13} stroke={2.5} className="mt-0.5 shrink-0" style={{ color: 'var(--text-muted)' }} />
                  <span className="text-sm leading-snug">{d.dropoffAddress ?? d.dropoffCity ?? d.zoneName}</span>
                </div>
              ) : (
                <span className="text-sm font-[500]" style={{ color: 'var(--text-muted)' }}>—</span>
              )}
              {d.clientPhone && (
                <p className="text-sm font-mono" style={{ color: 'var(--text-muted)' }}>{d.clientPhone}</p>
              )}
            </div>
          </div>

          {/* Schedule / slot / amount — subtle meta pills */}
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="inline-flex items-center gap-1.5 px-2 py-1 rounded-full" style={{ background: 'var(--hover-bg)', color: 'var(--text-muted)' }} title={formatShortDate(d.createdAt)}>
              <IconClock size={12} stroke={2.5} /> {t.dispatchDeskPage.cardCreated.replace('{time}', formatElapsed(alert?.updatedAt ?? d.createdAt, t))}
            </span>
            {d.scheduledAt && (
              <span className="inline-flex items-center gap-1.5 px-2 py-1 rounded-full" style={{ background: 'var(--hover-bg)', color: 'var(--text-muted)' }}>
                <IconCalendar size={12} stroke={2.5} />
                {new Date(d.scheduledAt).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })} {new Date(d.scheduledAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false })}
              </span>
            )}
            {slot && <span className="inline-flex items-center gap-1.5 px-2 py-1 rounded-full" style={{ background: 'var(--hover-bg)', color: 'var(--text-muted)' }}><IconCalendar size={12} stroke={2.5} /> {slot}</span>}
            {amount && <span className="inline-flex items-center px-2 py-1 rounded-full font-[700]" style={{ background: 'var(--brand-soft)', color: 'var(--brand)' }}>{amount}</span>}
          </div>

          {/* Items */}
          {d.items && d.items.length > 0 && (
            <div className="flex flex-col gap-1.5">
              <span className="text-2xs font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                {t.dispatchDeskPage.queueItemsLabel}
              </span>
              <div className="rounded-[var(--radius)] divide-y overflow-hidden border" style={{ background: 'var(--hover-bg)', borderColor: 'var(--border)' }}>
                {d.items.map((item, idx) => (
                  <div key={idx} className="flex items-center justify-between text-sm px-3 py-2" style={{ borderColor: 'var(--border)' }}>
                    <span className="truncate pr-2 font-[500]" style={{ color: 'var(--text-secondary)' }}>{item.name}</span>
                    <span className="font-mono font-semibold shrink-0" style={{ color: 'var(--text-primary)' }}>×{item.quantity}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
          </div>

          {/* Divider between the two columns — horizontal when stacked, vertical when side-by-side */}
          <Separator className="xl:hidden" />

          {/* ── RIGHT: activity rail (what happened & why) ─────────────────── */}
          <div className="flex flex-col gap-2 min-w-0 xl:w-[420px] xl:shrink-0 xl:border-s xl:ps-6" style={{ borderColor: 'var(--border)' }}>
            <span className="text-2xs font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
              {t.dispatchDeskPage.activityLabel ?? 'Activité'}
            </span>
            {id
              ? <SlaTimeline deliveryId={id} variant="detailed" />
              : (
                <div className="rounded-[var(--radius)] p-3.5" style={{ background: 'var(--hover-bg)', borderInlineStart: '3px solid var(--border)' }}>
                  <p className="text-base leading-relaxed font-[500]" style={{ color: 'var(--text-secondary)' }}>
                    {t.dispatchDeskPage.queueNoAlerts}
                  </p>
                </div>
              )}
          </div>
        </div>
      </ScrollArea>

      {/* Action bar — fixed footer, always visible regardless of scroll position */}
      {!isReadOnly && (
        <div className="shrink-0 flex items-center justify-end gap-2 px-5 py-3 border-t" style={{ background: 'var(--surface-sunken)', borderColor: 'var(--border)' }}>
          {canReassign && (
            <Button size="sm" className="h-8 px-3 text-xs font-bold rounded-md gap-1.5" onClick={() => setDrawerTargets([target])}>
              {d.driverId ? <IconReassign size={14} /> : <IconAssign size={14} />}
              {d.driverId ? t.dispatchDeskPage.buttonReassign : t.dispatchDeskPage.buttonAssign}
            </Button>
          )}
          {canReplan && (
            <Button
              size="sm" variant="outline" className="h-8 px-3 text-xs font-bold rounded-md gap-1.5"
              onClick={() => openActionModal('replan', alert ?? exceptionFromDelivery())}
            >
              <IconReplan size={14} />
              {t.dispatchDeskPage.buttonReplan}
            </Button>
          )}
          {needsClientContact(motif) && d.clientPhone && (
            <a
              href={`tel:${d.clientPhone}`}
              className="h-8 px-3 inline-flex items-center gap-1.5 text-xs font-bold rounded-md border transition-colors hover:opacity-80"
              style={{ color: '#059669', borderColor: '#059669' }}
            >
              <IconCall size={14} /> {t.dispatchDeskPage.buttonCallClient}
            </a>
          )}
          {needsDriverContact(motif) && d.driverId && (d.driverPhone ?? driver?.phone) && (
            <a
              href={`tel:${d.driverPhone ?? driver?.phone}`}
              className="h-8 px-3 inline-flex items-center gap-1.5 text-xs font-bold rounded-md border transition-colors hover:opacity-80"
              style={{ color: '#6366F1', borderColor: '#6366F1' }}
            >
              <IconCall size={14} /> {t.dispatchDeskPage.buttonCallDriver}
            </a>
          )}
          {alert && needsReturnToDepot(motif) && (
            <Button size="sm" variant="outline" className="h-8 px-3 text-xs font-bold rounded-md gap-1.5" onClick={() => setReturnTarget(alert)}>
              <IconArrowBack size={14} stroke={2.5} />
              {t.dispatchDeskPage.buttonReturnToDepot}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
