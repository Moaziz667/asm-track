import React from 'react';
import { Link } from 'react-router-dom';
import { IconCheck, IconMapPinOff } from '@tabler/icons-react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { AppLoader } from '@/components/AppLoader';
import StatusBadge from '@/components/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { formatMoney } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import { STATUS_DOT, getDriverStatusTip, SEVERITY_CHIP } from '../constants';
import { formatMotif, formatElapsed } from '../formatters';
import { rowId, isPinned } from '../utils';
import type { QueueRow } from '../types';

interface RowProps {
  row: QueueRow;
  active: boolean;
  checked: boolean;
  driverOnlineStatus?: string;
  onSelect: () => void;
  onToggle: () => void;
  t: ReturnType<typeof useT>;
}

function QueueListRow({ row, active, checked, driverOnlineStatus, onSelect, onToggle, t }: RowProps) {
  const { delivery: d, alert } = row;
  // The dot follows the unified SLA health (the same source as the detail SlaHealthBadge) so the
  // list dot and the badge can never disagree: BREACHED/LATE → red, AT_RISK → amber. Terminal or
  // failure exceptions (SLA health NONE/absent) fall back to the classifier severity.
  // Live health, falling back to the worst PAST phase health so a failed-but-was-late delivery
  // still reads as urgent (its live health is NONE).
  const liveHealth = (d.slaHealth ?? '').toUpperCase();
  const health = (liveHealth && liveHealth !== 'NONE') ? liveHealth : (d.slaWorstHealth ?? '').toUpperCase();
  const healthSev: 'CRITICAL' | 'WARNING' | null =
    health === 'BREACHED' || health === 'LATE' ? 'CRITICAL'
      : health === 'AT_RISK' ? 'WARNING'
      : null;
  const alertSev: 'CRITICAL' | 'WARNING' | 'INFO' | null = alert
    ? (alert.severity === 'CRITICAL' ? 'CRITICAL' : alert.severity === 'WARNING' ? 'WARNING' : 'INFO')
    : null;
  const sevKey: 'CRITICAL' | 'WARNING' | 'INFO' | null = healthSev ?? alertSev;
  const dotTip = alert ? formatMotif(alert.motif, t) : health || undefined;
  const amount = typeof d.totalAmount === 'number' && d.totalAmount > 0
    ? formatMoney(d.totalAmount, d.currency ?? 'TND')
    : null;
  const pinned = isPinned(d);

  return (
    <button
      type="button"
      onClick={onSelect}
      className="w-full text-start flex items-start gap-2 px-3 py-2 border-b transition-colors"
      style={{
        borderColor: 'var(--border)',
        // SLA-dominant left rail: overdue = red, at-risk = amber, healthy = none. The active
        // (selected) row still takes the brand rail so selection stays obvious.
        background: active
          ? 'var(--brand-soft)'
          : sevKey === 'CRITICAL' ? 'color-mix(in srgb, var(--danger) 5%, transparent)'
          : 'transparent',
        borderInlineStart: active
          ? '3px solid var(--brand)'
          : sevKey === 'CRITICAL' ? '3px solid var(--danger)'
          : sevKey === 'WARNING' ? '3px solid var(--warning)'
          : '3px solid transparent',
      }}
    >
      <input
        type="checkbox"
        className="w-3.5 h-3.5 mt-1 accent-[var(--brand)] shrink-0 disabled:opacity-40 disabled:cursor-not-allowed"
        checked={checked}
        disabled={!pinned}
        title={!pinned ? t.dispatchDeskPage.pinFirstTooltip : undefined}
        onClick={e => e.stopPropagation()}
        onChange={onToggle}
      />

      <div className="min-w-0 flex-1 flex flex-col gap-0.5">
        {/* Top line: severity dot · ref · client */}
        <div className="flex items-center gap-1.5 min-w-0">
          {sevKey && (
            <span
              className="w-1.5 h-1.5 rounded-full shrink-0"
              style={{ background: SEVERITY_CHIP[sevKey].accent }}
              title={dotTip}
            />
          )}
          <span className="font-mono text-xs font-[600] shrink-0" style={{ color: 'var(--brand)' }}>
            {d.orderRef ?? d.erpOrderId ?? rowId(d).slice(0, 8)}
          </span>
          <span className="text-sm font-[600] truncate" style={{ color: 'var(--text-primary)' }}>
            {d.clientName ?? '—'}
          </span>
          {!pinned && (
            <span
              className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full shrink-0 ms-auto text-2xs font-[500] leading-none"
              style={{ border: '1px solid color-mix(in srgb, var(--warning) 32%, transparent)', color: 'var(--warning)' }}
              title={t.dispatchDeskPage.pinFirstTooltip}
            >
              <IconMapPinOff size={10} stroke={2} />{t.dispatchDeskPage.needsPin}
            </span>
          )}
        </div>

        {/* Bottom line: status · driver · elapsed · amount */}
        <div className="flex items-center gap-1.5 min-w-0 text-xs" style={{ color: 'var(--text-muted)' }}>
          <StatusBadge status={d.status} size="sm" />
          {/* A delivery in a DRAFT route is UNSCHEDULED by status but not pool — keep the status and
              append the standard DRAFT badge (gray) so it reads as "being planned", not "non assigné". */}
          {d.status === 'UNSCHEDULED' && d.routeStatus === 'DRAFT' && (
            <StatusBadge status="DRAFT" size="sm" />
          )}
          <SlaHealthBadge health={d.slaHealth} />
          {d.driverName ? (
            <span className="inline-flex items-center gap-1 truncate" title={getDriverStatusTip(driverOnlineStatus, t)}>
              <span style={{ width: 5, height: 5, borderRadius: '50%', background: STATUS_DOT[driverOnlineStatus ?? 'OFFLINE'], flexShrink: 0 }} />
              <span className="truncate" style={{ maxWidth: 90 }}>{d.driverName}</span>
            </span>
          ) : (
            <span className="truncate">{t.dispatchDeskPage.unassignedLabel}</span>
          )}
          <span className="ms-auto shrink-0 font-mono">{formatElapsed(d.createdAt, t)}</span>
          {amount && <span className="shrink-0 font-[500]">· {amount}</span>}
        </div>
      </div>
    </button>
  );
}

export function QueueList() {
  const {
    t, allLoading, queueRows, drivers,
    selectedIds, toggleRow, selectedQueueId, setSelectedQueueId,
    queueSort, setQueueSort,
  } = useDispatchDeskContext();

  const dd = t.dispatchDeskPage;
  const sortOptions: { value: typeof queueSort; label: string }[] = [
    { value: 'sla',      label: dd.sortSla ?? 'Risque SLA' },
    { value: 'route',    label: dd.sortRoute ?? 'Tournée' },
    { value: 'severity', label: dd.sortSeverity ?? 'Sévérité' },
    { value: 'status',   label: dd.sortStatus ?? 'Statut' },
    { value: 'date',     label: dd.sortDate ?? 'Date' },
  ];
  const selectStyle: React.CSSProperties = {
    height: 26,
    borderRadius: 'var(--radius-md)',
    border: '1px solid var(--border)',
    background: 'var(--surface)',
    fontSize: 12,
    fontWeight: 500,
    color: 'var(--text-primary)',
    padding: '0 8px',
    outline: 'none',
    cursor: 'pointer',
  };

  const SortBar = (
    <div className="flex items-center gap-1.5 px-3 py-2 shrink-0 min-w-0" style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface)' }}>
      <span className="text-2xs font-[600] uppercase tracking-wide shrink-0 me-0.5" style={{ color: 'var(--text-muted)' }}>
        {dd.sortBy ?? 'Trier par'}
      </span>
      <select
        value={queueSort}
        onChange={(e) => setQueueSort(e.target.value as typeof queueSort)}
        className="flex-1 min-w-0"
        style={selectStyle}
      >
        {sortOptions.map(opt => (
          <option key={opt.value} value={opt.value}>{opt.label}</option>
        ))}
      </select>
    </div>
  );

  if (allLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <AppLoader size="sm" />
      </div>
    );
  }

  if (queueRows.length === 0) {
    return (
      <div className="flex-1 flex flex-col min-h-0">
        {SortBar}
        <div className="flex-1 flex flex-col items-center justify-center px-4 text-center">
          <IconCheck size={20} stroke={2.5} style={{ color: 'var(--text-soft)', marginBottom: 6 }} />
          <p className="text-sm font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.noActionRequired}</p>
        </div>
      </div>
    );
  }

  const renderRow = (row: QueueRow) => {
    const driver = drivers.find(dr => dr.id === row.delivery.driverId);
    return (
      <QueueListRow
        key={row.id}
        row={row}
        active={row.id === selectedQueueId}
        checked={selectedIds.has(row.id)}
        driverOnlineStatus={driver?.onlineStatus}
        onSelect={() => setSelectedQueueId(row.id)}
        onToggle={() => toggleRow(row.id)}
        t={t}
      />
    );
  };

  // Route-major sort → keep the route section headers; any other sort → flat list.
  if (queueSort !== 'route') {
    return (
      <div className="flex-1 flex flex-col min-h-0">
        {SortBar}
        <ScrollArea className="flex-1 min-h-0">
          {queueRows.map(renderRow)}
        </ScrollArea>
      </div>
    );
  }

  // Group consecutive rows by route (queueRows are pre-sorted route-major).
  const groups: { routeId?: string; routeName?: string; routeStatus?: string; rows: QueueRow[] }[] = [];
  for (const row of queueRows) {
    const last = groups[groups.length - 1];
    if (last && last.routeName === row.routeName) last.rows.push(row);
    else groups.push({ routeId: row.routeId, routeName: row.routeName, routeStatus: row.delivery.routeStatus, rows: [row] });
  }

  return (
    <div className="flex-1 flex flex-col min-h-0">
    {SortBar}
    <ScrollArea className="flex-1 min-h-0">
      {groups.map((group, gi) => (
        <div key={`${group.routeName ?? 'none'}-${gi}`}>
          <div
            className="sticky top-0 z-10 flex items-center gap-2 px-3 py-1.5"
            style={{ background: 'var(--surface-sunken)', borderBottom: '1px solid var(--border)', borderInlineStart: '3px solid transparent' }}
          >
            <span className="text-2xs font-[600] uppercase tracking-wide truncate" style={{ color: 'var(--text-secondary)' }}>
              {group.routeName ?? t.dispatchDeskPage.unassignedLabel}
            </span>
            {group.routeStatus === 'DRAFT' && (
              <span className="shrink-0"><StatusBadge status="DRAFT" size="sm" /></span>
            )}
            <span className="text-2xs font-[500] px-1.5 rounded-full shrink-0" style={{ background: 'var(--hover-bg)', color: 'var(--text-muted)', lineHeight: 1.6 }}>
              {group.rows.length}
            </span>
            {group.routeId && (
              <Link to={`/routes/${group.routeId}`} className="text-2xs font-[500] hover:underline ms-auto shrink-0" style={{ color: 'var(--brand)' }}>
                {t.dispatchDeskPage.openLink}
              </Link>
            )}
          </div>

          {group.rows.map(renderRow)}
        </div>
      ))}
    </ScrollArea>
    </div>
  );
}
