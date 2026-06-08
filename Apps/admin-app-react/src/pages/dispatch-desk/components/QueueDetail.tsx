import React from 'react';
import { Link } from 'react-router-dom';
import { IconArrowBack, IconCalendar, IconClock, IconMapPin, IconInbox } from '@tabler/icons-react';
import { IconAssign, IconReassign, IconReplan, IconCall } from '@/components/icons/DispatchIcons';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import StatusBadge from '@/components/StatusBadge';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import {
  REASSIGNABLE_STATUSES, REPLANNABLE_STATUSES, STATUS_DOT, getDriverStatusTip, SEVERITY_CHIP,
} from '../constants';
import {
  formatNarrative, formatElapsed, formatShortDate,
  needsClientContact, needsDriverContact, needsReturnToDepot,
} from '../formatters';
import { rowId } from '../utils';
import type { OpsException } from '../types';

function severityStyle(severity?: string) {
  const key: 'CRITICAL' | 'WARNING' | 'INFO' = severity === 'CRITICAL' ? 'CRITICAL' : severity === 'WARNING' ? 'WARNING' : 'INFO';
  return SEVERITY_CHIP[key];
}

export function QueueDetail() {
  const {
    t, isReadOnly, drivers, selectedQueueRow,
    setDrawerTargets, openActionModal, setReturnTarget,
  } = useDispatchDeskContext();

  if (!selectedQueueRow) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-2 px-6 text-center">
        <IconInbox size={28} stroke={1.5} style={{ color: 'var(--text-soft)' }} />
        <p className="text-[12.5px] font-[500]" style={{ color: 'var(--text-muted)' }}>
          {t.dispatchDeskPage.queueSelectPrompt}
        </p>
      </div>
    );
  }

  const { delivery: d, alert } = selectedQueueRow;
  const id = rowId(d);
  const driver = drivers.find(dr => dr.id === d.driverId);
  const sev = severityStyle(alert?.severity);
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
    ? `${d.totalAmount.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} TND`
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
      <ScrollArea className="flex-1 min-h-0">
        <div className="flex flex-col gap-5 p-5 max-w-[680px]">
          {/* Header */}
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <Link to={`/deliveries/${id}`} className="font-mono text-[12px] font-[600] hover:underline" style={{ color: 'var(--brand)' }}>
                {d.orderRef ?? d.erpOrderId ?? id.slice(0, 8)}
              </Link>
              <p className="text-[18px] font-bold truncate mt-0.5" style={{ color: 'var(--text-primary)' }}>{d.clientName ?? '—'}</p>
            </div>
            <div className="flex items-center gap-2.5 shrink-0">
              <StatusBadge status={d.status} size="sm" />
              <Link to={`/deliveries/${id}`} className="text-[11px] font-[500] hover:underline" style={{ color: 'var(--text-secondary)' }}>
                {t.dispatchDeskPage.openLink}
              </Link>
            </div>
          </div>

          {/* Narration block */}
          <div
            className="rounded-[var(--radius)] p-3.5"
            style={{
              background: alert ? sev.bg : 'var(--hover-bg)',
              borderInlineStart: alert ? `3px solid ${sev.accent}` : '3px solid var(--border)',
            }}
          >
            <p className="text-[13px] leading-relaxed font-[500]" style={{ color: alert ? 'var(--text-primary)' : 'var(--text-secondary)' }}>
              {alert ? formatNarrative(alert, t) : t.dispatchDeskPage.queueNoAlerts}
            </p>
          </div>

          <Separator />

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {/* Driver */}
            <div className="rounded-[var(--radius)] p-3 flex flex-col gap-1.5" style={{ background: 'var(--hover-bg)' }}>
              <span className="text-[10px] font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                {t.dispatchDeskPage.driverLabel}
              </span>
              {d.driverName ? (
                <div className="flex items-center gap-2">
                  <span style={{ width: 8, height: 8, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                  <div className="min-w-0">
                    <p className="text-[13px] font-[600]" style={{ color: 'var(--text-primary)' }}>{d.driverName}</p>
                    {(d.driverPhone || driver?.phone) && (
                      <p className="text-[11px]" style={{ color: 'var(--text-muted)' }}>{d.driverPhone ?? driver?.phone}</p>
                    )}
                  </div>
                  <span className="ms-auto text-[10px] font-[500] shrink-0" style={{ color: 'var(--text-muted)' }}>
                    {getDriverStatusTip(driver?.onlineStatus, t)}
                  </span>
                </div>
              ) : (
                <span className="text-[12.5px] font-[500]" style={{ color: 'var(--text-muted)' }}>
                  {t.dispatchDeskPage.unassignedLabel}
                </span>
              )}
            </div>

            {/* Address / client */}
            <div className="rounded-[var(--radius)] p-3 flex flex-col gap-1.5" style={{ background: 'var(--hover-bg)' }}>
              <span className="text-[10px] font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                {t.dispatchDeskPage.orderLabel}
              </span>
              {(d.dropoffAddress || d.dropoffCity || d.zoneName) ? (
                <div className="flex items-start gap-1.5" style={{ color: 'var(--text-secondary)' }}>
                  <IconMapPin size={13} stroke={2.5} className="mt-0.5 shrink-0" style={{ color: 'var(--text-muted)' }} />
                  <span className="text-[12.5px] leading-snug">{d.dropoffAddress ?? d.dropoffCity ?? d.zoneName}</span>
                </div>
              ) : (
                <span className="text-[12.5px] font-[500]" style={{ color: 'var(--text-muted)' }}>—</span>
              )}
              {d.clientPhone && (
                <p className="text-[12px] font-mono" style={{ color: 'var(--text-muted)' }}>{d.clientPhone}</p>
              )}
            </div>
          </div>

          {/* Schedule / slot / amount */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[12px]" style={{ color: 'var(--text-muted)' }}>
            <span className="inline-flex items-center gap-1.5" title={formatShortDate(d.createdAt)}>
              <IconClock size={13} stroke={2.5} /> {t.dispatchDeskPage.cardCreated.replace('{time}', formatElapsed(alert?.updatedAt ?? d.createdAt, t))}
            </span>
            {d.scheduledAt && (
              <span className="inline-flex items-center gap-1.5">
                <IconCalendar size={13} stroke={2.5} />
                {t.dispatchDeskPage.filterStatusScheduled}: {new Date(d.scheduledAt).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })} {new Date(d.scheduledAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false })}
              </span>
            )}
            {slot && <span className="inline-flex items-center gap-1.5"><IconCalendar size={13} stroke={2.5} /> {slot}</span>}
            {amount && <span className="font-[700]" style={{ color: 'var(--text-primary)' }}>{amount}</span>}
          </div>

          {/* Items */}
          {d.items && d.items.length > 0 && (
            <div className="flex flex-col gap-1.5">
              <span className="text-[10px] font-[600] uppercase tracking-wide" style={{ color: 'var(--text-soft)' }}>
                {t.dispatchDeskPage.queueItemsLabel}
              </span>
              <div className="rounded-[var(--radius)] divide-y overflow-hidden border" style={{ background: 'var(--hover-bg)', borderColor: 'var(--border)' }}>
                {d.items.map((item, idx) => (
                  <div key={idx} className="flex items-center justify-between text-[12px] px-3 py-2" style={{ borderColor: 'var(--border)' }}>
                    <span className="truncate pr-2 font-[500]" style={{ color: 'var(--text-secondary)' }}>{item.name}</span>
                    <span className="font-mono font-semibold shrink-0" style={{ color: 'var(--text-primary)' }}>×{item.quantity}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </ScrollArea>

      {/* Action bar — fixed footer, always visible regardless of scroll position */}
      {!isReadOnly && (
        <div className="shrink-0 flex items-center justify-end gap-2 px-5 py-3 border-t" style={{ background: 'var(--surface-sunken)', borderColor: 'var(--border)' }}>
          {canReassign && (
            <Button size="sm" className="h-8 px-3 text-[11.5px] font-bold rounded-md gap-1.5" onClick={() => setDrawerTargets([target])}>
              {d.driverId ? <IconReassign size={14} /> : <IconAssign size={14} />}
              {d.driverId ? t.dispatchDeskPage.buttonReassign : t.dispatchDeskPage.buttonAssign}
            </Button>
          )}
          {canReplan && (
            <Button
              size="sm" variant="outline" className="h-8 px-3 text-[11.5px] font-bold rounded-md gap-1.5"
              onClick={() => openActionModal('replan', alert ?? exceptionFromDelivery())}
            >
              <IconReplan size={14} />
              {t.dispatchDeskPage.buttonReplan}
            </Button>
          )}
          {needsClientContact(motif) && d.clientPhone && (
            <a
              href={`tel:${d.clientPhone}`}
              className="h-8 px-3 inline-flex items-center gap-1.5 text-[11.5px] font-bold rounded-md border transition-colors hover:opacity-80"
              style={{ color: '#059669', borderColor: '#059669' }}
            >
              <IconCall size={14} /> {t.dispatchDeskPage.buttonCallClient}
            </a>
          )}
          {needsDriverContact(motif) && d.driverId && (d.driverPhone ?? driver?.phone) && (
            <a
              href={`tel:${d.driverPhone ?? driver?.phone}`}
              className="h-8 px-3 inline-flex items-center gap-1.5 text-[11.5px] font-bold rounded-md border transition-colors hover:opacity-80"
              style={{ color: '#6366F1', borderColor: '#6366F1' }}
            >
              <IconCall size={14} /> {t.dispatchDeskPage.buttonCallDriver}
            </a>
          )}
          {alert && needsReturnToDepot(motif) && (
            <Button size="sm" variant="outline" className="h-8 px-3 text-[11.5px] font-bold rounded-md gap-1.5" onClick={() => setReturnTarget(alert)}>
              <IconArrowBack size={14} stroke={2.5} />
              {t.dispatchDeskPage.buttonReturnToDepot}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
