import React from 'react';
import { Link } from 'react-router-dom';
import { IconClock, IconCalendar, IconMapPin, IconCheck, IconMapPinOff } from '@tabler/icons-react';
import { IconAssign, IconReassign, IconReplan, IconCall } from '@/components/icons/DispatchIcons';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { AppLoader } from '@/components/AppLoader';
import StatusBadge from '@/components/StatusBadge';
import type { Delivery } from '@/types';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import { REASSIGNABLE_STATUSES, REPLANNABLE_STATUSES, STATUS_DOT, getDriverStatusTip, RIBBON, SEVERITY_CHIP } from '../constants';
import { formatMotif, formatElapsed, formatShortDate } from '../formatters';
import { rowId } from '../utils';
import type { OpsException } from '../types';

const CTA_ICON = 'w-7 h-7 flex items-center justify-center rounded shrink-0 transition-opacity hover:opacity-80';

/** Compact, labelled metadata line (created · time window · amount). */
function MetaLine({ d, t }: { d: Delivery; t: any }) {
  const created = formatElapsed(d.createdAt, t);
  const slot = d.timeSlotStartTime && d.timeSlotEndTime
    ? `${d.timeSlotStartTime.slice(0, 5)}–${d.timeSlotEndTime.slice(0, 5)}`
    : d.timeSlotName || (d.requestedDeliveryDate ? d.requestedDeliveryDate.slice(0, 10) : null);
  const amount = typeof d.totalAmount === 'number' && d.totalAmount > 0
    ? `${d.totalAmount.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} TND`
    : null;

  const scheduled = d.scheduledAt 
    ? new Date(d.scheduledAt).toLocaleDateString(undefined, { day: '2-digit', month: 'short' }) + ' ' + new Date(d.scheduledAt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false })
    : null;

  return (
    <div className="flex items-center flex-wrap gap-x-3 gap-y-1 text-[10px]" style={{ color: 'var(--text-muted)' }}>
      {scheduled ? (
        <span className="inline-flex items-center gap-1 font-bold text-[var(--text-soft)]" title={d.scheduledAt}>
          <IconCalendar size={11} stroke={2.5} /> {t.dispatchDeskPage.filterStatusScheduled}: {scheduled}
        </span>
      ) : (
        <span className="inline-flex items-center gap-1" title={formatShortDate(d.createdAt)}>
          <IconClock size={11} stroke={2.5} /> {t.dispatchDeskPage.cardCreated} {created}
        </span>
      )}
      {slot && <span className="inline-flex items-center gap-1"><IconCalendar size={11} stroke={2.5} /> {slot}</span>}
      {amount && <span className="font-[500]" style={{ color: 'var(--text-secondary)' }}>{amount}</span>}
    </div>
  );
}

export function DeliveryCards() {
  const {
    t, isReadOnly, allLoading, deliveryRows, dispatchTab, drivers, alertMap,
    selectedIds, toggleRow, setDrawerTargets, openActionModal, setFailedModalRow,
  } = useDispatchDeskContext();

  if (allLoading) {
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

  if (deliveryRows.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center" style={{ background: 'var(--app-bg)' }}>
        <IconCheck size={22} stroke={2.5} style={{ color: 'var(--text-soft)', marginBottom: 6 }} />
        <p className="text-[12px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.noOrdersFound}</p>
      </div>
    );
  }

  // Group consecutive rows by route (pre-sorted by route + severity).
  const groups: { routeId?: string; routeName?: string; rows: Delivery[] }[] = [];
  for (const d of deliveryRows) {
    const last = groups[groups.length - 1];
    if (last && last.routeName === d.routeName) last.rows.push(d);
    else groups.push({ routeId: d.routeId, routeName: d.routeName, rows: [d] });
  }

  const isFailedTab = dispatchTab === 'failed';
  const isGpsTab    = dispatchTab === 'gps';
  const isAssignTab = dispatchTab === 'assign';

  return (
    <div className="flex-1 overflow-auto p-3" style={{ background: 'var(--app-bg)' }}>
      {groups.map((group, gi) => (
        <section key={`${group.routeName ?? 'none'}-${gi}`} className="mb-4 last:mb-0">
          <div className="flex items-center gap-2 px-1 mb-2">
            <span className="text-[11px] font-[600] uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>
              {group.routeName ?? t.dispatchDeskPage.unassignedLabel}
            </span>
            <span className="text-[10px] font-[500] px-1.5 rounded-full" style={{ background: 'var(--hover-bg)', color: 'var(--text-muted)', lineHeight: 1.7 }}>
              {group.rows.length}
            </span>
            {group.routeId && (
              <Link to={`/routes/${group.routeId}`} className="text-[10px] font-[500] hover:underline ml-auto" style={{ color: 'var(--brand)' }}>
                {t.dispatchDeskPage.openLink}
              </Link>
            )}
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
            {group.rows.map(d => {
              const id        = rowId(d);
              const alert     = alertMap.get(id);
              const driver    = drivers.find(dr => dr.id === d.driverId);
              const isChecked = selectedIds.has(id);
              const accent    = alert ? SEVERITY_CHIP[alert.severity === 'CRITICAL' ? 'CRITICAL' : 'WARNING'].accent : (RIBBON[d.status] ?? '#94A3B8');
              const canAssign = (REASSIGNABLE_STATUSES as string[]).includes(d.status);
              const canReplan = (REPLANNABLE_STATUSES as string[]).includes(d.status);

              const created = formatElapsed(d.createdAt, t);
              const slot = d.timeSlotStartTime && d.timeSlotEndTime
                ? `${d.timeSlotStartTime.slice(0, 5)}–${d.timeSlotEndTime.slice(0, 5)}`
                : d.timeSlotName || (d.requestedDeliveryDate ? d.requestedDeliveryDate.slice(0, 10) : null);
              const amount = typeof d.totalAmount === 'number' && d.totalAmount > 0
                ? `${d.totalAmount.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} TND`
                : null;

              const target = {
                deliveryId: id, orderRef: d.orderRef, clientName: d.clientName,
                city: d.dropoffCity, status: d.status, driverName: d.driverName,
                routeId: d.routeId, routeName: d.routeName,
              };

              return (
                <article
                  key={id}
                  className={isFailedTab ? 'rounded-[var(--radius)] border flex flex-col cursor-pointer dispatch-card' : 'rounded-[var(--radius)] border flex flex-col dispatch-card'}
                  style={{
                    borderColor: isChecked ? 'var(--brand)' : 'var(--border)',
                    background: isChecked ? 'var(--brand-soft)' : 'var(--surface)',
                    borderLeft: `3px solid ${accent}`,
                  }}
                  onClick={isFailedTab ? () => setFailedModalRow(d) : undefined}
                >
                  <div className="p-3 flex flex-col gap-2">
                    {/* Header */}
                    <div className="flex items-start gap-2">
                      <input
                        type="checkbox"
                        className="w-3.5 h-3.5 mt-0.5 accent-[var(--brand)] shrink-0"
                        checked={isChecked}
                        onClick={e => e.stopPropagation()}
                        onChange={() => toggleRow(id)}
                      />
                      <div className="min-w-0 flex-1">
                        <Link
                          to={`/deliveries/${id}`}
                          onClick={e => e.stopPropagation()}
                          className="font-mono text-[12.5px] font-[600] hover:underline"
                          style={{ color: 'var(--brand)' }}
                        >
                          {d.orderRef ?? d.erpOrderId ?? id.slice(0, 8)}
                        </Link>
                        <p className="text-[14px] font-bold truncate mt-0.5" style={{ color: 'var(--text-primary)' }}>{d.clientName ?? '—'}</p>
                        {(d.dropoffAddress || d.dropoffCity || d.zoneName) && (
                          <div className="flex items-center gap-1 min-w-0 mt-0.5" style={{ color: 'var(--text-muted)' }}>
                            <IconMapPin size={10} stroke={2.5} className="shrink-0" />
                            <span className="text-[11.5px] truncate">{d.dropoffAddress ?? d.dropoffCity ?? d.zoneName}</span>
                          </div>
                        )}
                      </div>
                      {/* Per-tab right chip */}
                      {isAssignTab && alert && (
                        <span className="text-[11.5px] font-[600] px-1.5 py-0.5 rounded shrink-0"
                          style={{ color: SEVERITY_CHIP[alert.severity === 'CRITICAL' ? 'CRITICAL' : 'WARNING'].text, background: SEVERITY_CHIP[alert.severity === 'CRITICAL' ? 'CRITICAL' : 'WARNING'].bg }}>
                          {formatMotif(alert.motif, t)}
                        </span>
                      )}
                      {isGpsTab && (
                        <span className="text-[11.5px] font-[600] px-1.5 py-0.5 rounded shrink-0 inline-flex items-center gap-1"
                          style={{ color: SEVERITY_CHIP.CRITICAL.text, background: SEVERITY_CHIP.CRITICAL.bg }}>
                          <IconMapPinOff size={11} stroke={2.5} /> {t.dispatchDeskPage.missingGps}
                        </span>
                      )}
                    </div>

                    {/* Status + driver */}
                    <div className="flex flex-col gap-1.5">
                      <div className="flex items-center gap-2 flex-wrap">
                        <StatusBadge status={d.status} size="sm" />
                        {d.driverName ? (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <div className="flex items-center gap-1.5 cursor-default">
                                <div style={{ width: 6, height: 6, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                                <span className="text-[12.5px] font-[500] truncate" style={{ maxWidth: 120, color: 'var(--text-primary)' }}>{d.driverName}</span>
                              </div>
                            </TooltipTrigger>
                            <TooltipContent>{getDriverStatusTip(driver?.onlineStatus, t)}</TooltipContent>
                          </Tooltip>
                        ) : (
                          <span className="text-[11.5px] font-[500] px-1.5 py-0.5 rounded border" style={{ color: 'var(--text-muted)', borderColor: 'var(--border)' }}>
                            {t.dispatchDeskPage.unassignedLabel}
                          </span>
                        )}
                      </div>

                      {/* Colored scheduled date */}
                      {d.scheduledAt && (() => {
                        const scheduledDate = d.scheduledAt!.split('T')[0];
                        const now = new Date();
                        const todayStr = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().split('T')[0];
                        const isPending = ['SCHEDULED','PICKED_UP','IN_TRANSIT','UNSCHEDULED'].includes(d.status);
                        let color = 'var(--text-soft)';
                        if (isPending) {
                          if (scheduledDate < todayStr) color = '#EF4444';
                          else if (scheduledDate === todayStr) color = '#F59E0B';
                          else color = '#3B82F6';
                        }
                        const formatted = new Date(d.scheduledAt!).toLocaleDateString(undefined, { day: '2-digit', month: 'short' }) + ' ' + new Date(d.scheduledAt!).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false });
                        return (
                          <div className="flex items-center gap-1.5 text-[11.5px] font-semibold" style={{ color }}>
                            <IconCalendar size={11} stroke={2.5} />
                            <span>{t.dispatchDeskPage.filterStatusScheduled}: {formatted}</span>
                          </div>
                        );
                      })()}

                      {/* Articles list under status badge */}
                      {d.items && d.items.length > 0 && (
                        <div className="flex flex-col gap-1 mt-0.5 border-t border-[var(--border)]/20 pt-1.5">
                          {d.items.map((item, idx) => (
                            <div key={idx} className="flex items-center justify-between text-[11.5px] pl-0.5">
                              <span className="truncate pr-2 font-[500]" style={{ color: 'var(--text-secondary)' }}>{item.name}</span>
                              <span className="font-mono font-semibold shrink-0" style={{ color: 'var(--text-primary)' }}>×{item.quantity}</span>
                            </div>
                          ))}
                        </div>
                      )}

                      {slot && (
                        <div className="flex items-center gap-1.5 text-[11.5px] text-[var(--text-muted)] select-none pl-0.5">
                          <IconCalendar size={11} stroke={2.5} />
                          <span>{slot}</span>
                        </div>
                      )}
                    </div>
 
                    {/* Failure reason (failed tab) Callout */}
                    {isFailedTab && d.failureReason && (
                      <div className="text-[13px] leading-relaxed font-semibold text-blue-700 dark:text-blue-300 bg-blue-50/70 dark:bg-blue-950/30 border-l-2 border-blue-500 px-2.5 py-2 rounded-r">
                        {formatMotif(d.failureReason, t)}
                      </div>
                    )}
                  </div>
 
                  {/* Footer: age, amount + actions */}
                  <div className="flex items-center justify-between px-3 py-2 mt-auto border-t" style={{ borderColor: 'var(--border)' }} onClick={e => e.stopPropagation()}>
                    <div className="flex items-center flex-wrap gap-x-2 gap-y-0.5 text-[11.5px]" style={{ color: 'var(--text-muted)' }}>
                      <span className="inline-flex items-center gap-1 font-mono font-medium" title={formatShortDate(d.createdAt)}>
                        <IconClock size={11} stroke={2.5} />
                        <span>{t.dispatchDeskPage.cardCreated} {created}</span>
                      </span>
                      {amount && (
                        <span className="font-[600]" style={{ color: 'var(--text-secondary)' }}>
                          · {amount}
                        </span>
                      )}
                    </div>
                    {!isReadOnly && (
                      <div className="flex items-center gap-1">
                        {isAssignTab && canAssign && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <button type="button" className={CTA_ICON} style={{ background: 'var(--brand)', color: '#fff' }} onClick={() => setDrawerTargets([target])}>
                                {d.driverId ? <IconReassign size={15} /> : <IconAssign size={15} />}
                              </button>
                            </TooltipTrigger>
                            <TooltipContent>{d.driverId ? t.dispatchDeskPage.reassignTooltip : t.dispatchDeskPage.assignDriverTooltip}</TooltipContent>
                          </Tooltip>
                        )}
                        {isFailedTab && canReplan && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <button
                                type="button"
                                className={CTA_ICON}
                                style={{ background: 'var(--brand)', color: '#fff' }}
                                onClick={() => {
                                  const exc: OpsException = {
                                    deliveryId: id, orderId: d.orderId, orderRef: d.orderRef,
                                    routeId: d.routeId, routeName: d.routeName, status: d.status,
                                    motif: d.failureReason ?? d.status, driverId: d.driverId, driverName: d.driverName,
                                    clientName: d.clientName, city: d.dropoffCity, zoneName: d.zoneName,
                                    severity: 'WARNING', comment: d.failureReason, createdAt: d.createdAt, updatedAt: d.updatedAt,
                                  };
                                  openActionModal('replan', exc);
                                }}
                              >
                                <IconReplan size={15} />
                              </button>
                            </TooltipTrigger>
                            <TooltipContent>{t.dispatchDeskPage.replanTooltip}</TooltipContent>
                          </Tooltip>
                        )}
                        {isFailedTab && d.clientPhone && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <a href={`tel:${d.clientPhone}`} className={CTA_ICON} style={{ background: '#059669', color: '#fff' }}>
                                <IconCall size={15} />
                              </a>
                            </TooltipTrigger>
                            <TooltipContent>{`${t.dispatchDeskPage.callClientTooltip} · ${d.clientPhone}`}</TooltipContent>
                          </Tooltip>
                        )}
                        {isGpsTab && (
                          <Link to={`/deliveries/${id}`} className="text-[10px] font-[600] underline" style={{ color: '#6366F1' }}>
                            {t.dispatchDeskPage.fixGps}
                          </Link>
                        )}
                      </div>
                    )}
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      ))}
    </div>
  );
}
