'use client';

import { Link } from 'react-router-dom';
import { IconChevronDown, IconChevronRight, IconArrowBack } from '@tabler/icons-react';
import { IconAssign, IconReassign, IconReplan, IconCall } from '@/components/icons/DispatchIcons';
import { Tooltip, TooltipContent, TooltipTrigger, TooltipProvider } from '@/components/ui/tooltip';
import StatusBadge from '@/components/StatusBadge';
import type { Driver } from '@/types';
import type { OpsException, ActionKind } from './types';
import { REASSIGNABLE_STATUSES, REPLANNABLE_STATUSES, STATUS_DOT, STATUS_TIP } from './constants';
import { formatMotif, formatElapsed, formatComment, formatSuggestion, formatShortDate, needsClientContact, needsDriverContact, needsReturnToDepot } from './formatters';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';

interface Props {
  row: OpsException;
  idx: number;
  isChecked: boolean;
  isExpanded: boolean;
  isReadOnly: boolean;
  driver?: Driver;
  clientPhone?: string;
  driverPhone?: string;
  runningAction: string | null;
  onToggleCheck: () => void;
  onToggleExpand: () => void;
  onAction: (kind: ActionKind, row: OpsException) => void;
  onReturnToDepot: (row: OpsException) => void;
}

type CtaButton = {
  tip: string;
  icon: React.ReactNode;
  color: string;
  onClick?: () => void;
  href?: string;
  external?: boolean;
};

function getCtaButtons(
  row: OpsException,
  onAction: (kind: ActionKind, row: OpsException) => void,
  onReturnToDepot: (row: OpsException) => void,
  t: any,
  clientPhone?: string,
  driverPhone?: string,
): CtaButton[] {
  const motif = (row.motif ?? '').toUpperCase().trim();
  const btns: CtaButton[] = [];

  if (REASSIGNABLE_STATUSES.includes(row.status)) {
    btns.push({
      tip: row.driverId ? t.dispatchDeskPage.buttonReassign : t.dispatchDeskPage.buttonAssign,
      icon: row.driverId ? <IconReassign size={16} /> : <IconAssign size={16} />,
      color: '#C4881A',
      onClick: () => onAction('reassign', row),
    });
  }

  if (REPLANNABLE_STATUSES.includes(row.status) && !REASSIGNABLE_STATUSES.includes(row.status)) {
    btns.push({
      tip: t.dispatchDeskPage.buttonReplan,
      icon: <IconReplan size={16} />,
      color: '#C4881A',
      onClick: () => onAction('replan', row),
    });
  }

  if (needsClientContact(motif)) {
    if (clientPhone) {
      btns.push({ tip: `${t.dispatchDeskPage.buttonCallClient} · ${clientPhone}`, icon: <IconCall size={16} />, color: '#059669', href: `tel:${clientPhone}`, external: true });
    } else {
      btns.push({ tip: t.dispatchDeskPage.buttonContactClient, icon: <IconCall size={16} />, color: '#059669', href: `/deliveries/${row.deliveryId}` });
    }
  }

  if (needsDriverContact(motif) && row.driverId) {
    if (driverPhone) {
      btns.push({ tip: `${t.dispatchDeskPage.buttonCallDriver} · ${driverPhone}`, icon: <IconCall size={16} />, color: '#6366F1', href: `tel:${driverPhone}`, external: true });
    }
  }

  if (needsReturnToDepot(motif)) {
    btns.push({ tip: t.dispatchDeskPage.buttonReturnToDepot, icon: <IconArrowBack size={14} stroke={2.5} />, color: 'var(--brand)', onClick: () => onReturnToDepot(row) });
  }

  return btns;
}

// Shared action-icon style
const ACTION_ICON_BASE = 'w-7 h-7 flex items-center justify-center rounded shrink-0 transition-opacity hover:opacity-80';

export function ActionRow({
  row, idx, isChecked, isExpanded, isReadOnly,
  driver, clientPhone, driverPhone, runningAction,
  onToggleCheck, onToggleExpand, onAction, onReturnToDepot,
}: Props) {
  const t = useT();
  const ribbonColor = row.severity === 'CRITICAL' ? '#EF4444' : row.severity === 'WARNING' ? '#F59E0B' : '#A3A3A3';
  const canAct = !isReadOnly && (REASSIGNABLE_STATUSES.includes(row.status) || REPLANNABLE_STATUSES.includes(row.status));
  const ctaBtns = canAct ? getCtaButtons(row, onAction, onReturnToDepot, t, clientPhone, driverPhone) : [];

  return (
    <TooltipProvider>
      <>
        <tr className={cn('dispatch-row')} style={isChecked ? { background: 'var(--brand-soft)', verticalAlign: 'top' } : { verticalAlign: 'top' }}>
          {/* Checkbox */}
          <td onClick={e => e.stopPropagation()} style={{ padding: '10px 8px' }}>
            <input
              type="checkbox"
              className="w-3.5 h-3.5 accent-[var(--brand)]"
              checked={isChecked}
              onChange={onToggleCheck}
            />
          </td>

          {/* Order ref */}
          <td style={{ padding: '8px 10px', borderLeft: `3px solid ${ribbonColor}` }}>
            <Link to={`/deliveries/${row.deliveryId}`} className="font-mono text-[11px] font-[500] hover:underline" style={{ color: '#C4881A' }}>
              {row.orderRef ?? row.deliveryId.slice(0, 8)}
            </Link>
            <p className="text-[10px] font-mono mt-0.5" style={{ color: 'var(--text-muted)' }}>{formatElapsed(row.updatedAt ?? row.createdAt)}</p>
          </td>

          {/* Status */}
          <td style={{ padding: '8px 12px' }}>
            <StatusBadge status={row.status} size="sm" />
          </td>

          {/* Client */}
          <td style={{ padding: '8px 12px' }}>
            <p className="text-[12px] font-semibold truncate" style={{ maxWidth: 140, color: 'var(--text-primary)' }}>{row.clientName ?? '—'}</p>
            {row.city && <p className="text-[10px] mt-0.5" style={{ color: 'var(--text-muted)' }}>{row.city}</p>}
          </td>

          {/* Driver */}
          <td style={{ padding: '8px 12px' }}>
            {row.driverName ? (
              <Tooltip>
                <TooltipTrigger asChild>
                  <div className="flex items-center gap-1.5 cursor-default">
                    <div style={{ width: 6, height: 6, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                    <span className="text-[11px] font-semibold truncate" style={{ maxWidth: 110, color: 'var(--text-primary)' }}>{row.driverName}</span>
                  </div>
                </TooltipTrigger>
                <TooltipContent>{STATUS_TIP[driver?.onlineStatus ?? 'OFFLINE']}</TooltipContent>
              </Tooltip>
            ) : (
              <span className="text-[10px] font-medium px-1.5 py-0.5 rounded border" style={{ color: 'var(--text-muted)', borderColor: 'var(--border)', background: 'transparent' }}>
                {t.dispatchDeskPage.unassignedLabel}
              </span>
            )}
            {row.routeName && <p className="text-[10px] mt-0.5" style={{ color: 'var(--text-muted)' }}>{row.routeName}</p>}
          </td>

          {/* Motif */}
          <td style={{ padding: '8px 12px' }}>
            <span
              className="text-[10px] font-semibold px-1.5 py-0.5 rounded"
              style={{
                color: row.severity === 'CRITICAL' ? '#dc2626' : row.severity === 'WARNING' ? '#ea580c' : 'var(--text-muted)',
                background: row.severity === 'CRITICAL' ? 'rgba(220,38,38,0.10)' : row.severity === 'WARNING' ? 'rgba(234,88,12,0.10)' : 'var(--hover-bg)',
              }}
            >
              {formatMotif(row.motif)}
            </span>
          </td>

          {/* CTA buttons */}
          <td onClick={e => e.stopPropagation()} style={{ padding: '8px 10px', width: 80 }}>
            <div className="flex items-center gap-1 flex-nowrap action-reveal">
              {ctaBtns.map((btn, i) =>
                btn.external ? (
                  <Tooltip key={i}>
                    <TooltipTrigger asChild>
                      <a href={btn.href!} className={ACTION_ICON_BASE} style={{ background: btn.color, color: '#fff' }}>
                        {btn.icon}
                      </a>
                    </TooltipTrigger>
                    <TooltipContent>{btn.tip}</TooltipContent>
                  </Tooltip>
                ) : btn.href ? (
                  <Tooltip key={i}>
                    <TooltipTrigger asChild>
                      <Link to={btn.href!} className={ACTION_ICON_BASE} style={{ background: btn.color, color: '#fff' }}>
                        {btn.icon}
                      </Link>
                    </TooltipTrigger>
                    <TooltipContent>{btn.tip}</TooltipContent>
                  </Tooltip>
                ) : (
                  <Tooltip key={i}>
                    <TooltipTrigger asChild>
                      <button
                        type="button"
                        onClick={btn.onClick}
                        className={ACTION_ICON_BASE}
                        style={{ background: btn.color, color: '#fff' }}
                      >
                        {btn.icon}
                      </button>
                    </TooltipTrigger>
                    <TooltipContent>{btn.tip}</TooltipContent>
                  </Tooltip>
                )
              )}
            </div>
          </td>

          {/* Expand chevron */}
          <td onClick={e => e.stopPropagation()} style={{ width: 32, textAlign: 'center', padding: '8px 4px' }}>
            <button
              type="button"
              className="w-7 h-7 flex items-center justify-center rounded border border-[var(--border)] text-[var(--text-muted)] hover:bg-[var(--hover-bg)] transition-colors"
              onClick={onToggleExpand}
            >
              {isExpanded ? <IconChevronDown size={14} stroke={2.5} /> : <IconChevronRight size={14} stroke={2.5} />}
            </button>
          </td>
        </tr>

        {/* Expanded detail panel */}
        {isExpanded && (
          <tr style={{ background: 'var(--app-bg)' }}>
            <td style={{ padding: 0, width: 3 }}>
              <div style={{ width: 3, height: '100%', background: ribbonColor }} />
            </td>
            <td colSpan={8} style={{ padding: '12px 16px' }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>

                {/* Col 1 — Incident */}
                <div className="flex flex-col gap-1.5">
                  <span className="text-[10px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.incidentLabel}</span>
                  <div className="flex items-center gap-1.5 flex-wrap">
                    <span
                      className="text-[11px] font-semibold px-1.5 py-0.5 rounded"
                      style={{
                        color: row.severity === 'CRITICAL' ? '#fff' : row.severity === 'WARNING' ? '#fff' : '#fff',
                        background: row.severity === 'CRITICAL' ? '#dc2626' : row.severity === 'WARNING' ? '#ea580c' : '#71717a',
                      }}
                    >
                      {formatMotif(row.motif)}
                    </span>
                    {row.severity === 'CRITICAL' && (
                      <span className="text-[10px] font-semibold px-1.5 py-0.5 rounded border border-[#dc2626]" style={{ color: '#dc2626' }}>
                        {t.dispatchDeskPage.criticalLabel}
                      </span>
                    )}
                  </div>
                  <p className="text-[12px] leading-relaxed" style={{ color: 'var(--text-primary)' }}>{formatComment(row)}</p>
                  {formatSuggestion(row) && (
                    <p className="text-[11px] font-bold" style={{ color: '#C4881A' }}>{formatSuggestion(row)}</p>
                  )}
                  <div className="flex gap-3 mt-0.5">
                    <div>
                      <p className="text-[10px]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.reportedLabel}</p>
                      <p className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{formatShortDate(row.createdAt)}</p>
                    </div>
                    {row.updatedAt && row.updatedAt !== row.createdAt && (
                      <div>
                        <p className="text-[10px]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.updatedLabel}</p>
                        <p className="text-[11px] font-semibold" style={{ color: 'var(--text-primary)' }}>{formatShortDate(row.updatedAt)}</p>
                      </div>
                    )}
                  </div>
                </div>

                {/* Col 2 — Client + Driver */}
                <div className="flex flex-col gap-2.5">
                  <div>
                    <p className="text-[10px] font-[500] mb-1" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.clientLabel}</p>
                    <p className="text-[12px] font-semibold" style={{ color: 'var(--text-primary)' }}>{row.clientName ?? '—'}</p>
                    {row.city && (
                      <p className="text-[11px]" style={{ color: 'var(--text-muted)' }}>{row.city}{row.zoneName ? ` · ${row.zoneName}` : ''}</p>
                    )}
                    {clientPhone && (
                      <p className="text-[11px] mt-0.5 font-semibold" style={{ color: '#C4881A' }}>{clientPhone}</p>
                    )}
                  </div>
                  {row.driverName && (
                    <div>
                      <p className="text-[10px] font-[500] mb-1" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.driverLabel}</p>
                      <div className="flex items-center gap-1.5">
                        <div style={{ width: 7, height: 7, borderRadius: '50%', background: STATUS_DOT[driver?.onlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
                        <span className="text-[12px] font-semibold" style={{ color: 'var(--text-primary)' }}>{row.driverName}</span>
                      </div>
                      {driverPhone && (
                        <p className="text-[11px] mt-0.5 font-semibold" style={{ color: '#C4881A' }}>{driverPhone}</p>
                      )}
                      <p className="text-[10px] mt-0.5" style={{ color: 'var(--text-muted)' }}>{STATUS_TIP[driver?.onlineStatus ?? 'OFFLINE']}</p>
                    </div>
                  )}
                </div>

                {/* Col 3 — Route */}
                <div className="flex flex-col gap-1.5">
                  <span className="text-[10px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.routeLabel}</span>
                  {row.routeId ? (
                    <>
                      <Link to={`/routes/${row.routeId}`} style={{ textDecoration: 'none' }}>
                        <span className="text-[12px] font-bold" style={{ color: '#C4881A' }}>
                          {row.routeName ?? row.routeId.slice(0, 8)}
                        </span>
                      </Link>
                      {row.routeStatus && (
                        <span
                          className="text-[10px] font-semibold px-1.5 py-0.5 rounded w-fit"
                          style={{
                            color: row.routeStatus === 'IN_PROGRESS' ? '#059669' : row.routeStatus === 'VALIDATED' ? '#2563eb' : '#ca8a04',
                            background: row.routeStatus === 'IN_PROGRESS' ? 'rgba(5,150,105,0.12)' : row.routeStatus === 'VALIDATED' ? 'rgba(37,99,235,0.12)' : 'rgba(202,138,4,0.12)',
                          }}
                        >
                          {row.routeStatus === 'IN_PROGRESS' ? t.dispatchDeskPage.routeInProgress : row.routeStatus === 'VALIDATED' ? t.dispatchDeskPage.routeValidated : t.dispatchDeskPage.routeDraft}
                        </span>
                      )}
                    </>
                  ) : (
                    <span className="text-[11px]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.noRouteAssigned}</span>
                  )}
                </div>

              </div>
            </td>
          </tr>
        )}
      </>
    </TooltipProvider>
  );
}
