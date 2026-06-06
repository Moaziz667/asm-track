import React from 'react';
import { Link } from 'react-router-dom';
import { IconArrowBack, IconCheck, IconClock, IconCalendar } from '@tabler/icons-react';
import { IconAssign, IconReassign, IconReplan, IconCall } from '@/components/icons/DispatchIcons';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { AppLoader } from '@/components/AppLoader';
import StatusBadge from '@/components/StatusBadge';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import {
  REASSIGNABLE_STATUSES, REPLANNABLE_STATUSES, STATUS_DOT, getDriverStatusTip, SEVERITY_CHIP,
} from '../constants';
import {
  formatMotif, formatComment, formatElapsed, formatShortDate, formatSuggestion,
  needsClientContact, needsDriverContact, needsReturnToDepot,
} from '../formatters';
import type { OpsException } from '../types';
import type { Delivery } from '@/types';

// Pastel severity palette, aligned with the StatusBadge Linear tones.
function severityStyle(severity?: string): { accent: string; chipColor: string; chipBg: string } {
  const c = SEVERITY_CHIP[severity === 'CRITICAL' ? 'CRITICAL' : severity === 'WARNING' ? 'WARNING' : 'INFO'];
  return { accent: c.accent, chipColor: c.text, chipBg: c.bg };
}

const CTA_ICON = 'w-7 h-7 flex items-center justify-center rounded shrink-0 transition-opacity hover:opacity-80';

export function ActionCards() {
  const {
    t, isReadOnly, loading, actionRows, drivers, deliveryMap,
    selectedIds, toggleRow, openActionModal, setReturnTarget,
  } = useDispatchDeskContext();

  if (loading) {
    return (
      <div className="flex-1 overflow-auto p-3" style={{ background: 'var(--app-bg)' }}>
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="rounded-[var(--radius)] p-4" style={{ background: 'var(--surface)', boxShadow: 'var(--shadow-card)' }}>
              <AppLoader size="sm" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (actionRows.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center" style={{ background: 'var(--app-bg)' }}>
        <IconCheck size={22} stroke={2.5} style={{ color: 'var(--text-soft)', marginBottom: 6 }} />
        <p className="text-[12px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.noActionRequired}</p>
      </div>
    );
  }

  // Group consecutive rows by route (rows are pre-sorted by route + severity).
  const groups: { routeId?: string; routeName?: string; rows: OpsException[] }[] = [];
  for (const row of actionRows) {
    const last = groups[groups.length - 1];
    if (last && last.routeName === row.routeName) last.rows.push(row);
    else groups.push({ routeId: row.routeId, routeName: row.routeName, rows: [row] });
  }

  return (
    <div className="flex-1 overflow-auto p-3" style={{ background: 'var(--app-bg)' }}>
      {groups.map((group, gi) => (
        <section key={`${group.routeName ?? 'none'}-${gi}`} className="mb-4 last:mb-0">
          {/* Route header — AWS resource-group style, subtle */}
          <div className="flex items-center gap-2 px-1 mb-2">
            <span className="text-[11px] font-[600] uppercase tracking-wide" style={{ color: 'var(--text-secondary)' }}>
              {group.routeName ?? t.dispatchDeskPage.noRouteAssigned}
            </span>
            <span
              className="text-[10px] font-[500] px-1.5 rounded-full"
              style={{ background: 'var(--hover-bg)', color: 'var(--text-muted)', lineHeight: 1.7 }}
            >
              {group.rows.length}
            </span>
            {group.routeId && (
              <Link to={`/routes/${group.routeId}`} className="text-[10px] font-[500] hover:underline ml-auto" style={{ color: 'var(--brand)' }}>
                {t.dispatchDeskPage.openLink}
              </Link>
            )}
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
            {group.rows.map(row => {
              const sev    = severityStyle(row.severity);
              const driver = drivers.find(d => d.id === row.driverId);
              const clientPhone = deliveryMap.get(row.deliveryId)?.clientPhone;
              const driverPhone = driver?.phone;
              const motif  = (row.motif ?? '').toUpperCase().trim();
              const isChecked = selectedIds.has(row.deliveryId);
              const suggestion = formatSuggestion(row, t);

              const d = deliveryMap.get(row.deliveryId);
              const created = formatElapsed(row.updatedAt ?? row.createdAt, t);
              const slot = d?.timeSlotStartTime && d?.timeSlotEndTime
                ? `${d.timeSlotStartTime.slice(0, 5)}–${d.timeSlotEndTime.slice(0, 5)}`
                : d?.timeSlotName || (d?.requestedDeliveryDate ? d.requestedDeliveryDate.slice(0, 10) : null);
              const amount = d && typeof d.totalAmount === 'number' && d.totalAmount > 0
                ? `${d.totalAmount.toLocaleString('fr-FR', { maximumFractionDigits: 2 })} TND`
                : null;

              const canReassign = (REASSIGNABLE_STATUSES as string[]).includes(row.status);
              const canReplan   = (REPLANNABLE_STATUSES as string[]).includes(row.status) && !canReassign;

              return (
                <article
                  key={row.deliveryId}
                  className="rounded-[var(--radius)] flex flex-col dispatch-card"
                  style={{
                    background: isChecked ? 'var(--brand-soft)' : 'var(--surface)',
                    borderLeft: `3px solid ${sev.accent}`,
                    boxShadow: isChecked
                      ? `inset 0 0 0 2px var(--brand), var(--shadow-card)`
                      : 'var(--shadow-card)',
                  }}
                >
                  <div className="p-3 flex flex-col gap-2">
                    {/* Header: checkbox + ref + motif chip */}
                    <div className="flex items-start gap-2">
                      <input
                        type="checkbox"
                        className="w-3.5 h-3.5 mt-0.5 accent-[var(--brand)] shrink-0"
                        checked={isChecked}
                        onChange={() => toggleRow(row.deliveryId)}
                      />
                      <div className="min-w-0 flex-1">
                        <Link to={`/deliveries/${row.deliveryId}`} className="font-mono text-[12.5px] font-[600] hover:underline" style={{ color: 'var(--brand)' }}>
                          {row.orderRef ?? row.deliveryId.slice(0, 8)}
                        </Link>
                        <p className="text-[14px] font-bold truncate mt-0.5" style={{ color: 'var(--text-primary)' }}>
                          {row.clientName ?? '—'}
                        </p>
                        {(row.city || row.zoneName) && (
                          <p className="text-[11.5px] truncate" style={{ color: 'var(--text-muted)' }}>
                            {row.city ?? row.zoneName}{row.zoneName && row.city ? ` · ${row.zoneName}` : ''}
                          </p>
                        )}
                      </div>
                      <span className="text-[11.5px] font-[600] px-1.5 py-0.5 rounded shrink-0" style={{ color: sev.chipColor, background: sev.chipBg }}>
                        {formatMotif(row.motif, t)}
                      </span>
                    </div>

                    {/* Status + driver */}
                    <div className="flex flex-col gap-1.5">
                      <div className="flex items-center gap-2 flex-wrap">
                        <StatusBadge status={row.status} size="sm" />
                        {row.driverName ? (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <div className="flex items-center gap-1.5 cursor-default">
                                <div style={{ width: 6, height: 6, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                                <span className="text-[12.5px] font-[500] truncate" style={{ maxWidth: 120, color: 'var(--text-primary)' }}>{row.driverName}</span>
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
                      {d?.scheduledAt && (() => {
                        const scheduledDate = d.scheduledAt!.split('T')[0];
                        const now = new Date();
                        const todayStr = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().split('T')[0];
                        const isPending = ['SCHEDULED','PICKED_UP','IN_TRANSIT','UNSCHEDULED'].includes(d!.status);
                        let color = 'var(--text-soft)';
                        if (isPending) {
                          if (scheduledDate < todayStr) color = '#EF4444';
                          else if (scheduledDate === todayStr) color = '#F59E0B';
                          else color = '#3B82F6';
                        }
                        const formatted = new Date(d!.scheduledAt!).toLocaleDateString(undefined, { day: '2-digit', month: 'short' }) + ' ' + new Date(d!.scheduledAt!).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hour12: false });
                        return (
                          <div className="flex items-center gap-1.5 text-[11.5px] font-semibold" style={{ color }}>
                            <IconCalendar size={11} stroke={2.5} />
                            <span>{t.dispatchDeskPage.filterStatusScheduled}: {formatted}</span>
                          </div>
                        );
                      })()}

                      {/* Articles list under status badge */}
                      {d?.items && d.items.length > 0 && (
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

                    {/* Incident detail Callout */}
                    <div className="text-[13px] leading-relaxed font-semibold text-blue-700 dark:text-blue-300 bg-blue-50/70 dark:bg-blue-950/30 border-l-2 border-blue-500 px-2.5 py-2 rounded-r">
                      {formatComment(row, t)}
                    </div>
                    {suggestion && <p className="text-[11.5px] font-semibold" style={{ color: 'var(--brand)' }}>{suggestion}</p>}
                  </div>

                  {/* Footer: age, amount + actions */}
                  <div className="flex items-center justify-between px-3 py-2 mt-auto border-t" style={{ borderColor: 'var(--border)' }}>
                    <div className="flex items-center flex-wrap gap-x-2 gap-y-0.5 text-[11.5px]" style={{ color: 'var(--text-muted)' }}>
                      <span className="inline-flex items-center gap-1 font-mono font-medium" title={formatShortDate(row.createdAt)}>
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
                        {canReassign && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <button type="button" className={CTA_ICON} style={{ background: 'var(--brand)', color: '#fff' }} onClick={() => openActionModal('reassign', row)}>
                                {row.driverId ? <IconReassign size={15} /> : <IconAssign size={15} />}
                              </button>
                            </TooltipTrigger>
                            <TooltipContent>{row.driverId ? t.dispatchDeskPage.buttonReassign : t.dispatchDeskPage.buttonAssign}</TooltipContent>
                          </Tooltip>
                        )}
                        {canReplan && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <button type="button" className={CTA_ICON} style={{ background: 'var(--brand)', color: '#fff' }} onClick={() => openActionModal('replan', row)}>
                                <IconReplan size={15} />
                              </button>
                            </TooltipTrigger>
                            <TooltipContent>{t.dispatchDeskPage.buttonReplan}</TooltipContent>
                          </Tooltip>
                        )}
                        {needsClientContact(motif) && clientPhone && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <a href={`tel:${clientPhone}`} className={CTA_ICON} style={{ background: '#059669', color: '#fff' }}>
                                <IconCall size={15} />
                              </a>
                            </TooltipTrigger>
                            <TooltipContent>{`${t.dispatchDeskPage.buttonCallClient} · ${clientPhone}`}</TooltipContent>
                          </Tooltip>
                        )}
                        {needsDriverContact(motif) && row.driverId && driverPhone && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <a href={`tel:${driverPhone}`} className={CTA_ICON} style={{ background: '#6366F1', color: '#fff' }}>
                                <IconCall size={15} />
                              </a>
                            </TooltipTrigger>
                            <TooltipContent>{`${t.dispatchDeskPage.buttonCallDriver} · ${driverPhone}`}</TooltipContent>
                          </Tooltip>
                        )}
                        {needsReturnToDepot(motif) && (
                          <Tooltip>
                            <TooltipTrigger asChild>
                              <button type="button" className={CTA_ICON} style={{ background: 'var(--brand)', color: '#fff' }} onClick={() => setReturnTarget(row)}>
                                <IconArrowBack size={14} stroke={2.5} />
                              </button>
                            </TooltipTrigger>
                            <TooltipContent>{t.dispatchDeskPage.buttonReturnToDepot}</TooltipContent>
                          </Tooltip>
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
