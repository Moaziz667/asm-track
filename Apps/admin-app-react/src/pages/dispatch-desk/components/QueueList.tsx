import React from 'react';
import { Link } from 'react-router-dom';
import { IconCheck } from '@tabler/icons-react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { AppLoader } from '@/components/AppLoader';
import StatusBadge from '@/components/StatusBadge';
import SlaHealthBadge from '@/components/data-display/SlaHealthBadge';
import { formatMoney } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { useDispatchDeskContext } from '../hooks/useDispatchDeskState';
import { STATUS_DOT, getDriverStatusTip, SEVERITY_CHIP } from '../constants';
import { formatMotif, formatElapsed } from '../formatters';
import { rowId } from '../utils';
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
  const sevKey: 'CRITICAL' | 'WARNING' | 'INFO' | null = alert
    ? (alert.severity === 'CRITICAL' ? 'CRITICAL' : alert.severity === 'WARNING' ? 'WARNING' : 'INFO')
    : null;
  const amount = typeof d.totalAmount === 'number' && d.totalAmount > 0
    ? formatMoney(d.totalAmount, d.currency ?? 'TND')
    : null;

  return (
    <button
      type="button"
      onClick={onSelect}
      className="w-full text-start flex items-start gap-2 px-3 py-2 border-b transition-colors"
      style={{
        borderColor: 'var(--border)',
        background: active ? 'var(--brand-soft)' : 'transparent',
        borderInlineStart: active ? '3px solid var(--brand)' : '3px solid transparent',
      }}
    >
      <input
        type="checkbox"
        className="w-3.5 h-3.5 mt-1 accent-[var(--brand)] shrink-0"
        checked={checked}
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
              title={formatMotif(alert!.motif, t)}
            />
          )}
          <span className="font-mono text-[11px] font-[600] shrink-0" style={{ color: 'var(--brand)' }}>
            {d.orderRef ?? d.erpOrderId ?? rowId(d).slice(0, 8)}
          </span>
          <span className="text-[12.5px] font-[600] truncate" style={{ color: 'var(--text-primary)' }}>
            {d.clientName ?? '—'}
          </span>
        </div>

        {/* Bottom line: status · driver · elapsed · amount */}
        <div className="flex items-center gap-1.5 min-w-0 text-[11px]" style={{ color: 'var(--text-muted)' }}>
          <StatusBadge status={d.status} size="sm" />
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
  } = useDispatchDeskContext();

  if (allLoading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <AppLoader size="sm" />
      </div>
    );
  }

  if (queueRows.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center px-4 text-center">
        <IconCheck size={20} stroke={2.5} style={{ color: 'var(--text-soft)', marginBottom: 6 }} />
        <p className="text-[12px] font-[500]" style={{ color: 'var(--text-muted)' }}>{t.dispatchDeskPage.noActionRequired}</p>
      </div>
    );
  }

  // Group consecutive rows by route (queueRows are pre-sorted route-major).
  const groups: { routeId?: string; routeName?: string; rows: QueueRow[] }[] = [];
  for (const row of queueRows) {
    const last = groups[groups.length - 1];
    if (last && last.routeName === row.routeName) last.rows.push(row);
    else groups.push({ routeId: row.routeId, routeName: row.routeName, rows: [row] });
  }

  return (
    <ScrollArea className="flex-1 min-h-0">
      {groups.map((group, gi) => (
        <div key={`${group.routeName ?? 'none'}-${gi}`}>
          <div
            className="sticky top-0 z-10 flex items-center gap-2 px-3 py-1.5"
            style={{ background: 'var(--surface-sunken)', borderBottom: '1px solid var(--border)' }}
          >
            <span className="text-[10px] font-[600] uppercase tracking-wide truncate" style={{ color: 'var(--text-secondary)' }}>
              {group.routeName ?? t.dispatchDeskPage.unassignedLabel}
            </span>
            <span className="text-[9px] font-[500] px-1.5 rounded-full shrink-0" style={{ background: 'var(--hover-bg)', color: 'var(--text-muted)', lineHeight: 1.6 }}>
              {group.rows.length}
            </span>
            {group.routeId && (
              <Link to={`/routes/${group.routeId}`} className="text-[9px] font-[500] hover:underline ms-auto shrink-0" style={{ color: 'var(--brand)' }}>
                {t.dispatchDeskPage.openLink}
              </Link>
            )}
          </div>

          {group.rows.map(row => {
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
          })}
        </div>
      ))}
    </ScrollArea>
  );
}
