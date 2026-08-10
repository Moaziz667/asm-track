import React from 'react';
import type { Delivery, DeliveryItem } from '@/types';
import { Link } from 'react-router-dom';
import { IconCalendar, IconClock, IconMapPin, IconMapPinOff, IconInbox, IconPhone, IconCheck } from '@tabler/icons-react';
import { IconAssign, IconReassign, IconReplan, IconCall } from '@/components/icons/DispatchIcons';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import SlaTimeline from '@/components/data-display/SlaTimeline';
import { ArticlesTable } from '@/components/data-display/ArticlesTable';
import { DriverNote } from '@/components/data-display/DriverNote';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import {
  REASSIGNABLE_STATUSES, REPLANNABLE_STATUSES, getDriverStatusTip,
} from '../constants';
import {
  formatElapsed, formatShortDate,
  needsClientContact, needsDriverContact,
} from '../formatters';
import { rowId, isPinned } from '../utils';
import type { OpsException } from '../types';
import { cn, formatMoney } from '@/lib/utils';
import { formatAddress } from '@/lib/utils/address';

const driverStatusTone = (status?: string) => {
  if (status === 'ONLINE') return 'text-[var(--success)]';
  if (status === 'ON_BREAK') return 'text-[var(--warning)]';
  return 'text-[var(--text-muted)]';
};

export function QueueDetail() {
  const {
    t, isReadOnly, drivers, selectedQueueRow,
    setDrawerTargets, openActionModal, setAckTarget,
  } = useDispatchDeskContext();

  const selectedId = selectedQueueRow ? rowId(selectedQueueRow.delivery) : '';
  const { data: detail } = useQuery({
    queryKey: ['queue-delivery-detail', selectedId],
    enabled: !!selectedId,
    staleTime: 15000,
    queryFn: async () => {
      const [dRes, podRes] = await Promise.allSettled([
        api.get(`/admin/deliveries/${selectedId}`),
        api.get(`/admin/deliveries/${selectedId}/pod`),
      ]);
      return {
        delivery: dRes.status === 'fulfilled' ? dRes.value.data : null,
        pod: podRes.status === 'fulfilled' ? podRes.value.data : null,
      };
    },
  });

  if (!selectedQueueRow) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-2 px-6 text-center">
        <IconInbox size={24} stroke={1.5} className="text-[var(--text-soft)]" />
        <p className="text-sm font-medium text-[var(--text-muted)]">
          {t.dispatchDeskPage.queueSelectPrompt}
        </p>
      </div>
    );
  }

  const { delivery: d, alert } = selectedQueueRow;
  const id = rowId(d);
  const driver = drivers.find(dr => dr.id === d.driverId);
  const motif = (alert?.motif ?? '').toUpperCase().trim();
  const detailDelivery = (detail?.delivery ?? null) as Delivery | null;
  const detailItems: DeliveryItem[] = detailDelivery?.items ?? d.items ?? [];
  const failMotif: string | null = detailDelivery?.failReason ?? null;
  const driverNote: string | null = detail?.pod?.comment ?? detailDelivery?.failureComment ?? null;

  const canReassign = (REASSIGNABLE_STATUSES as string[]).includes(d.status);
  const canReplan   = (REPLANNABLE_STATUSES as string[]).includes(d.status) && !canReassign;
  const pinned = isPinned(d);

  const liveHealth = d.slaHealth ?? alert?.slaHealth;
  const worstHealth = d.slaWorstHealth;
  const effectiveSlaHealth = (!liveHealth || liveHealth === 'NONE')
    ? (worstHealth && worstHealth !== 'NONE' ? worstHealth : liveHealth)
    : liveHealth;
  const wasLatePastPhase = (worstHealth === 'BREACHED' || worstHealth === 'LATE');
  const lateNote = (wasLatePastPhase && (liveHealth === 'NONE' || !liveHealth) && (d.slaLateMinutes ?? 0) > 0)
    ? (t.dispatchDeskPage.wasLateBy ?? 'était en retard +{n}min').replace('{n}', String(d.slaLateMinutes))
    : '';

  const target = {
    deliveryId: id, orderRef: d.orderRef, erpOrderId: d.erpOrderId, clientName: d.clientName,
    city: d.dropoffCity, status: d.status, driverName: d.driverName,
    routeId: d.routeId, routeName: d.routeName, routeStatus: d.routeStatus,
    timeSlotStartTime: d.timeSlotStartTime, timeSlotEndTime: d.timeSlotEndTime,
    timeSlotName: d.timeSlotName, requestedDeliveryDate: d.requestedDeliveryDate,
    totalWeightKg: d.totalWeightKg, totalAmount: d.totalAmount, currency: d.currency,
    itemsCount: d.items?.length, priority: d.priority, scheduledAt: d.scheduledAt,
    dropoffAddress: d.dropoffAddress,
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

  const scheduledDateTime = d.scheduledAt
    ? `${new Date(d.scheduledAt).toLocaleDateString(undefined, { day: '2-digit', month: 'short' })} ${new Date(d.scheduledAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false })}`
    : null;

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-[var(--app-bg)]">
      <ScrollArea className="flex-1 min-h-0">
        <div className="flex flex-col xl:flex-row xl:items-stretch gap-5 xl:gap-6 p-5">

          {/* ── LEFT: context ──────────────────────────────────────────────── */}
          <div className="flex flex-col gap-5 min-w-0 xl:flex-1 xl:max-w-[620px]">
            {/* Header */}
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="text-xl font-bold text-[var(--text-primary)] truncate leading-tight">
                  {d.clientName ?? '—'}
                </p>
                <div className="flex items-center gap-2 mt-1">
                  <Link to={`/deliveries/${id}`} className="font-mono text-xs font-semibold text-[var(--brand)] hover:underline">
                    {d.orderRef ?? d.erpOrderId ?? id.slice(0, 8)}
                  </Link>
                  {lateNote && (
                    <span className="text-2xs font-semibold text-[var(--danger)]">{lateNote}</span>
                  )}
                </div>
              </div>
              <div className="flex flex-col items-end gap-1.5 shrink-0">
                <div className="flex items-center gap-1.5">
                  <StatusBadge status={d.status} size="sm" />
                  <SlaHealthBadge health={effectiveSlaHealth} size="sm" />
                </div>
                <Link to={`/deliveries/${id}`} className="text-2xs font-semibold text-[var(--brand)] hover:underline inline-flex items-center gap-0.5">
                  {t.dispatchDeskPage.openLink}
                </Link>
              </div>
            </div>

            {/* Driver + Address — side-by-side on sm+, stacked on mobile */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {/* Driver */}
              <div className="flex flex-col gap-2">
                <span className="text-2xs font-medium text-[var(--text-muted)]">
                  {t.dispatchDeskPage.driverLabel}
                </span>
                {d.driverName ? (
                  <div className="flex items-center gap-2">
                    <DriverAvatarById driverId={d.driverId} name={d.driverName} size={28} />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-semibold text-[var(--text-primary)] truncate">{d.driverName}</p>
                      {(d.driverPhone || driver?.phone) && (
                        <p className="text-2xs text-[var(--text-muted)]">{d.driverPhone ?? driver?.phone}</p>
                      )}
                    </div>
                    <span className={cn('text-2xs font-medium shrink-0', driverStatusTone(driver?.onlineStatus))}>
                      {getDriverStatusTip(driver?.onlineStatus, t)}
                    </span>
                  </div>
                ) : (
                  <span className="text-sm font-medium text-[var(--text-muted)]">
                    {t.dispatchDeskPage.unassignedLabel}
                  </span>
                )}
              </div>

              {/* Address */}
              <div className="flex flex-col gap-2">
                <span className="text-2xs font-medium text-[var(--text-muted)]">
                  {t.dispatchDeskPage.addressLabel ?? 'Adresse'}
                </span>
                {(d.dropoffAddress || d.dropoffCity || d.zoneName) ? (
                  <div className="flex items-start gap-1.5 text-[var(--text-secondary)]">
                    <IconMapPin size={13} stroke={2.5} className="mt-0.5 shrink-0 text-[var(--text-muted)]" />
                    <span className="text-sm leading-snug" title={d.dropoffAddress ?? undefined}>{formatAddress(d.dropoffAddress) || d.dropoffCity || d.zoneName}</span>
                  </div>
                ) : (
                  <span className="text-sm font-medium text-[var(--text-muted)]">—</span>
                )}
                {d.clientPhone && (
                  <p className="text-2xs font-mono text-[var(--text-muted)] inline-flex items-center gap-1">
                    <IconPhone size={10} stroke={2} /> {d.clientPhone}
                  </p>
                )}
              </div>
            </div>

            <Separator />

            {/* Meta line */}
            <div className="flex flex-wrap items-center gap-3 text-2xs text-[var(--text-muted)]">
              <span className="inline-flex items-center gap-1" title={formatShortDate(d.createdAt)}>
                <IconClock size={11} stroke={2.5} /> {t.dispatchDeskPage.cardCreated.replace('{time}', formatElapsed(d.createdAt, t))}
              </span>
              {scheduledDateTime && (
                <span className="inline-flex items-center gap-1">
                  <IconCalendar size={11} stroke={2.5} /> {scheduledDateTime}
                </span>
              )}
              {slot && <span className="inline-flex items-center gap-1"><IconCalendar size={11} stroke={2.5} /> {slot}</span>}
              {amount && <span className="font-semibold text-[var(--brand)]">{amount}</span>}
            </div>

            {/* Items */}
            {detailItems.length > 0 && (
              <div className="flex flex-col gap-1.5">
                <span className="text-2xs font-medium text-[var(--text-muted)]">
                  {t.dispatchDeskPage.queueItemsLabel}
                </span>
                <div className="rounded-md overflow-hidden border border-[var(--border)]">
                  <ArticlesTable
                    items={detailItems}
                    status={d.status}
                    failureCode={detailDelivery?.failureCode}
                    failMotif={failMotif}
                    currency={detailDelivery?.currency ?? d.currency}
                  />
                </div>
              </div>
            )}
          </div>

          <Separator className="xl:hidden" />

          {/* ── RIGHT: activity rail ───────────────────────────────────────── */}
          <div className="flex flex-col gap-4 min-w-0 xl:w-[420px] xl:shrink-0 xl:border-s xl:ps-6 border-[var(--border)]">
            {driverNote && (
              <div className="flex flex-col gap-1.5">
                <span className="text-2xs font-medium text-[var(--text-muted)]">
                  {t.deliveryPage.driverNoteLabel}
                </span>
                <DriverNote comment={driverNote} driverName={d.driverName} emptyLabel={t.deliveryPage.driverNoteEmpty} />
              </div>
            )}
            <div className="flex flex-col gap-2">
                <span className="text-2xs font-medium text-[var(--text-muted)]">
                  {t.dispatchDeskPage.activityLabel ?? 'Activité'}
                </span>
              {id
                ? <SlaTimeline deliveryId={id} variant="detailed" hideItemOutcomes hidePodComment hideFailureContext />
                : (
                  <div className="rounded-md p-3 bg-[var(--hover-bg)]">
                    <p className="text-sm leading-relaxed font-medium text-[var(--text-secondary)]">
                      {t.dispatchDeskPage.queueNoAlerts}
                    </p>
                  </div>
                )}
            </div>
          </div>
        </div>
      </ScrollArea>

      {/* Action bar */}
      {!isReadOnly && (
        <div className="shrink-0 flex items-center justify-start gap-2 px-5 py-3 border-t bg-[var(--app-bg)] border-[var(--border)]">
          {canReassign && pinned && (
            <Button size="sm" className="h-8 px-3 text-xs font-bold rounded-md gap-1.5" onClick={() => setDrawerTargets([target])}>
              {d.driverId ? <IconReassign size={14} /> : <IconAssign size={14} />}
              {d.driverId ? t.dispatchDeskPage.buttonReassign : t.dispatchDeskPage.buttonAssign}
            </Button>
          )}
          {canReassign && !pinned && (
            <Link
              to={`/deliveries?pin=${id}`}
              className="inline-flex items-center justify-center gap-1 h-7 px-2.5 rounded-md border text-xs font-bold whitespace-nowrap transition-all border-[var(--warning)] text-[var(--warning)] hover:bg-[var(--warning-bg)]"
            >
              <IconMapPinOff size={14} /> {t.dispatchDeskPage.pinAddress}
            </Link>
          )}
          {canReplan && (
            <Button
              size="sm"
              className="h-8 px-3 text-xs font-bold rounded-md gap-1.5"
              onClick={() => openActionModal('replan', alert ?? exceptionFromDelivery())}
            >
              <IconReplan size={14} />
              {t.dispatchDeskPage.buttonReplan}
            </Button>
          )}
          {/*
            Offered only on a finished attempt, the one place nothing else can close the exception:
            a customer who cancelled by telephone, refused the remainder for good, an address that
            does not exist. On a delivery still in the field the exception *is* the work — plan it,
            call the driver — and a "handled" button there would hide the job instead of doing it.
          */}
          {(d.status === 'FAILED' || d.status === 'PARTIALLY_DELIVERED') && (
            <Button
              size="sm"
              variant="outline"
              className="h-8 px-3 text-xs font-bold rounded-md gap-1.5"
              onClick={() => setAckTarget(alert ?? exceptionFromDelivery())}
            >
              <IconCheck size={14} />
              {t.dispatchDeskPage.buttonAcknowledge}
            </Button>
          )}
          {needsClientContact(motif) && d.clientPhone && (
            <a
              href={`tel:${d.clientPhone}`}
              className="inline-flex items-center justify-center gap-1 h-7 px-2.5 rounded-md border text-xs font-bold whitespace-nowrap transition-all border-[var(--success)] text-[var(--success)] hover:bg-[var(--success-bg)]"
            >
              <IconCall size={14} /> {t.dispatchDeskPage.buttonCallClient}
            </a>
          )}
          {needsDriverContact(motif) && d.driverId && (d.driverPhone ?? driver?.phone) && (
            <a
              href={`tel:${d.driverPhone ?? driver?.phone}`}
              className="inline-flex items-center justify-center gap-1 h-8 px-3 rounded-md text-xs font-bold whitespace-nowrap transition-all bg-[var(--info)] text-white hover:opacity-90"
            >
              <IconCall size={14} /> {t.dispatchDeskPage.buttonCallDriver}
            </a>
          )}
        </div>
      )}
    </div>
  );
}
