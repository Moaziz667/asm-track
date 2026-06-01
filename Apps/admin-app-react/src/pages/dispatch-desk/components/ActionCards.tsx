import React from 'react';
import { Link } from 'react-router-dom';
import { IconArrowBack, IconCheck } from '@tabler/icons-react';
import { IconAssign, IconReassign, IconReplan, IconCall } from '@/components/icons/DispatchIcons';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { AppLoader } from '@/components/AppLoader';
import StatusBadge from '@/components/StatusBadge';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import {
  REASSIGNABLE_STATUSES, REPLANNABLE_STATUSES, STATUS_DOT, STATUS_TIP, SEVERITY_CHIP,
} from '../constants';
import {
  formatMotif, formatComment, formatElapsed, formatSuggestion,
  needsClientContact, needsDriverContact, needsReturnToDepot,
} from '../formatters';
import type { OpsException } from '../types';

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
            <div key={i} className="rounded-[var(--radius)] border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
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
        <IconCheck size={22} style={{ color: 'var(--text-soft)', marginBottom: 6 }} />
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

              const canReassign = (REASSIGNABLE_STATUSES as string[]).includes(row.status);
              const canReplan   = (REPLANNABLE_STATUSES as string[]).includes(row.status) && !canReassign;

              return (
                <article
                  key={row.deliveryId}
                  className="rounded-[var(--radius)] border flex flex-col"
                  style={{
                    borderColor: isChecked ? 'var(--brand)' : 'var(--border)',
                    background: isChecked ? 'var(--brand-soft)' : 'var(--surface)',
                    borderLeft: `3px solid ${sev.accent}`,
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
                        <Link to={`/deliveries/${row.deliveryId}`} className="font-mono text-[11px] font-[600] hover:underline" style={{ color: 'var(--brand)' }}>
                          {row.orderRef ?? row.deliveryId.slice(0, 8)}
                        </Link>
                        <p className="text-[12px] font-[600] truncate mt-0.5" style={{ color: 'var(--text-primary)' }}>
                          {row.clientName ?? '—'}
                        </p>
                        {(row.city || row.zoneName) && (
                          <p className="text-[10px] truncate" style={{ color: 'var(--text-muted)' }}>
                            {row.city ?? row.zoneName}{row.zoneName && row.city ? ` · ${row.zoneName}` : ''}
                          </p>
                        )}
                      </div>
                      <span className="text-[10px] font-[600] px-1.5 py-0.5 rounded shrink-0" style={{ color: sev.chipColor, background: sev.chipBg }}>
                        {formatMotif(row.motif, t)}
                      </span>
                    </div>

                    {/* Status + driver */}
                    <div className="flex items-center gap-2 flex-wrap">
                      <StatusBadge status={row.status} size="sm" />
                      {row.driverName ? (
                        <Tooltip>
                          <TooltipTrigger asChild>
                            <div className="flex items-center gap-1.5 cursor-default">
                              <div style={{ width: 6, height: 6, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                              <span className="text-[11px] font-[500] truncate" style={{ maxWidth: 120, color: 'var(--text-primary)' }}>{row.driverName}</span>
                            </div>
                          </TooltipTrigger>
                          <TooltipContent>{STATUS_TIP[driver?.onlineStatus ?? 'OFFLINE']}</TooltipContent>
                        </Tooltip>
                      ) : (
                        <span className="text-[10px] font-[500] px-1.5 py-0.5 rounded border" style={{ color: 'var(--text-muted)', borderColor: 'var(--border)' }}>
                          {t.dispatchDeskPage.unassignedLabel}
                        </span>
                      )}
                    </div>

                    {/* Incident detail */}
                    <p className="text-[11px] leading-relaxed" style={{ color: 'var(--text-secondary)' }}>{formatComment(row, t)}</p>
                    {suggestion && <p className="text-[10px] font-[500]" style={{ color: 'var(--brand)' }}>{suggestion}</p>}
                  </div>

                  {/* Footer: age + actions */}
                  <div className="flex items-center justify-between px-3 py-2 mt-auto border-t" style={{ borderColor: 'var(--border)' }}>
                    <span className="text-[10px] font-mono" style={{ color: 'var(--text-muted)' }}>
                      {formatElapsed(row.updatedAt ?? row.createdAt, t)}
                    </span>
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
                                <IconArrowBack size={14} />
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
