import { useState, useRef, useCallback, useMemo } from 'react';
import type { TranslationSchema } from '@/lib/i18n/LocaleContext';
import { tlabel } from '@/lib/i18n/i18n-dict';
import { Link } from 'react-router-dom';
import { IconClock, IconArrowRight } from '@tabler/icons-react';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import { useDensity } from '@/hooks/useDensity';
import { useOpsSettings, HANDOFF_PENDING_KEY, HANDOFF_PENDING_DEFAULT } from '@/hooks/useOpsSettings';
import { cardView, phaseOf } from './HandoffCards';
import { HandoffDetailModal } from './HandoffDetailModal';
import type { HandoffItem } from '../types';
import type { ColumnDef } from '@/hooks/useColumnSettings';

interface Props {
  items: HandoffItem[];
  t: TranslationSchema;
}

const HANDOFF_HISTORY_COLUMNS: ColumnDef[] = [
  { id: 'status',   label: 'Status',    pinned: true },
  { id: 'client',   label: 'Client' },
  { id: 'transfer', label: 'From → To' },
  { id: 'ref',      label: 'Ref' },
  { id: 'date',     label: 'Date' },
  { id: 'duration', label: 'Duration' },
];

const COL_TRACKS: Record<string, string> = {
  status:   'minmax(100px, 1fr)',
  client:   'minmax(120px, 1.4fr)',
  transfer: 'minmax(180px, 2fr)',
  ref:      'minmax(90px, 1fr)',
  date:     'minmax(110px, 1fr)',
  duration: 'minmax(80px, 0.8fr)',
};

const ROW_H: Record<string, number> = { compact: 40, comfortable: 56, spacious: 68 };

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

function durationOf(h: HandoffItem): string | null {
  if (h.state !== 'CONFIRMED' || !h.requestedAt || !h.confirmedAt) return null;
  const mins = Math.max(0, Math.round((new Date(h.confirmedAt).getTime() - new Date(h.requestedAt).getTime()) / 60000));
  return fmtMinutes(mins);
}

function endedAt(h: HandoffItem): string | undefined {
  return h.confirmedAt ?? h.cancelledAt ?? h.expiredAt ?? h.requestedAt;
}

/** Floating tooltip shown on row hover — quick preview without clicking. */
function HoverTooltip({ h, t, rect, pendingMinutes }: { h: HandoffItem; t: TranslationSchema; rect: DOMRect; pendingMinutes: number }) {
  const view = cardView(h, t, pendingMinutes);
  const dur = durationOf(h);
  const byLabel = t.dispatchDeskPage.handoffByLabel ?? 'by';

  return (
    <div
      className="fixed z-50 pointer-events-none"
      style={{ top: rect.top - 8, left: rect.right + 12 }}
    >
      <div
        className="rounded-[var(--radius-xl)] p-3 flex flex-col gap-2 min-w-[220px]"
        style={{ background: 'var(--surface)', border: '1px solid var(--border)', boxShadow: 'var(--shadow-dropdown)' }}
      >
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-[600] truncate" style={{ color: 'var(--text-primary)' }}>
            {h.clientName ?? '—'}
          </span>
          <span className="text-2xs font-[600] shrink-0" style={{ color: view.accent }}>
            {view.label}
          </span>
        </div>
        <div className="flex items-center gap-1.5 text-2xs" style={{ color: 'var(--text-muted)' }}>
          <span className="font-[600]">{h.fromDriverName ?? '—'}</span>
          <IconArrowRight size={10} stroke={2.5} style={{ color: 'var(--text-soft)' }} />
          <span className="font-[600]">{h.toDriverName ?? '—'}</span>
        </div>
        <div className="flex items-center gap-1.5 text-2xs font-mono" style={{ color: 'var(--text-muted)' }}>
          <IconClock size={10} stroke={2} />
          {fmtTs(endedAt(h))}
          {dur && <span>· {dur}</span>}
        </div>
        {h.reason && (
          <p className="text-2xs truncate" style={{ color: 'var(--text-secondary)' }}>
            {byLabel} {h.reason}
          </p>
        )}
      </div>
    </div>
  );
}

export function HandoffHistoryTable({ items, t }: Props) {
  const [detail, setDetail] = useState<HandoffItem | null>(null);
  const [hover, setHover] = useState<{ h: HandoffItem; rect: DOMRect } | null>(null);
  const hoverTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  const { density, setDensity } = useDensity('handoff-history', 'comfortable');
  const pendingMinutes = useOpsSettings().getInt(HANDOFF_PENDING_KEY, HANDOFF_PENDING_DEFAULT);
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } =
    useColumnSettings('handoff-history', HANDOFF_HISTORY_COLUMNS);

  // One ordered list drives the tracks, the labels AND the cells. Sorting pinned columns first for
  // the tracks only (as this did) is what slid the open table's cells into the wrong columns. The
  // trailing track holds the header's settings gear, reserved on the rows too so labels stay put.
  const activeColumns = useMemo(
    () => orderedColumns.filter(c => c.pinned || visibleIds.has(c.id)),
    [orderedColumns, visibleIds],
  );
  const gridCols = useMemo(
    () => [...activeColumns.map(c => COL_TRACKS[c.id] ?? '1fr'), '44px'].join(' '),
    [activeColumns],
  );

  const onRowEnter = useCallback((e: React.MouseEvent, h: HandoffItem) => {
    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
    hoverTimeout.current = setTimeout(() => setHover({ h, rect }), 400);
  }, []);

  const onRowLeave = useCallback(() => {
    if (hoverTimeout.current) clearTimeout(hoverTimeout.current);
    setHover(null);
  }, []);

  if (items.length === 0) return null;

  return (
    <>
      {/* Table */}
      <div className="flex-1 overflow-y-auto">
        <div className="rounded-[var(--radius-xl)] overflow-hidden" style={{ background: 'var(--surface)', border: '1px solid var(--border)', boxShadow: 'var(--shadow-card)' }}>
          {/* Header */}
          <div
            className="sticky top-0 z-10 grid items-center h-[44px] px-4 border-b border-[var(--border)]"
            style={{ gridTemplateColumns: gridCols, background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}
          >
            {activeColumns.map(col => (
              <span key={col.id} className="text-xs font-semibold text-[var(--text-muted)] text-start truncate">
                {tlabel(t.dispatchDeskPage, `handoffCol${col.id.charAt(0).toUpperCase() + col.id.slice(1)}`) ?? col.label}
              </span>
            ))}
            <div className="flex items-center justify-end">
              <DisplaySettingsDropdown
                columns={orderedColumns}
                visibleIds={visibleIds}
                onToggle={toggleColumn}
                onReorder={moveColumn}
                onReset={resetColumns}
                density={density}
                onDensityChange={setDensity}
              />
            </div>
          </div>

          {/* Rows */}
          {items.map((h, idx) => {
            const view = cardView(h, t, pendingMinutes);
            const dur = durationOf(h);
            return (
              <div key={h.id || idx} className="border-b border-[var(--border)]" style={{ background: 'var(--surface)' }}>
                <div
                  className="grid items-center cursor-pointer group hover:bg-[var(--hover-bg)] transition-colors px-4"
                  style={{ gridTemplateColumns: gridCols, height: ROW_H[density] }}
                  onClick={() => setDetail(h)}
                  onMouseEnter={(e) => onRowEnter(e, h)}
                  onMouseLeave={onRowLeave}
                >
                  {/* Status (pinned) */}
                  <div className="flex items-center gap-2 text-start min-w-0">
                    <span className="rounded-full shrink-0" style={{ width: 6, height: 6, background: view.accent }} aria-hidden />
                    <span className="text-xs font-[600] truncate" style={{ color: view.accent }}>
                      {view.label}
                    </span>
                  </div>

                  {/* Client */}
                  {visibleIds.has('client') && (
                    <div className="text-start min-w-0">
                      <span className="text-sm font-[600] truncate block" style={{ color: 'var(--text-primary)' }}>
                        {h.clientName ?? '—'}
                      </span>
                    </div>
                  )}

                  {/* Transfer */}
                  {visibleIds.has('transfer') && (
                    <div className="flex items-center gap-2 text-start min-w-0">
                      <span className="flex items-center shrink-0">
                        <DriverAvatarById driverId={h.fromDriverId} name={h.fromDriverName} size={22} />
                        <span className="flex items-center" style={{ marginInlineStart: -7, boxShadow: '0 0 0 2px var(--surface)', borderRadius: '50%' }}>
                          <DriverAvatarById driverId={h.toDriverId} name={h.toDriverName} size={22} />
                        </span>
                      </span>
                      <span className="flex items-center gap-1.5 min-w-0 text-xs">
                        <span className="truncate" style={{ color: 'var(--text-secondary)' }}>{h.fromDriverName ?? '—'}</span>
                        <IconArrowRight size={12} stroke={2.5} className="shrink-0" style={{ color: 'var(--text-soft)' }} />
                        <span className="truncate font-[600]" style={{ color: 'var(--text-primary)' }}>{h.toDriverName ?? '—'}</span>
                      </span>
                    </div>
                  )}

                  {/* Ref */}
                  {visibleIds.has('ref') && (
                    <div className="text-start">
                      {h.deliveryId ? (
                        <Link
                          to={`/deliveries/${h.deliveryId}`}
                          onClick={(e) => e.stopPropagation()}
                          className="font-mono text-xs font-[600] hover:underline"
                          style={{ color: 'var(--brand)' }}
                        >
                          {h.erpOrderId || h.deliveryId.slice(0, 8)}
                        </Link>
                      ) : (
                        <span className="font-mono text-xs font-[600]" style={{ color: 'var(--text-soft)' }}>—</span>
                      )}
                    </div>
                  )}

                  {/* Date */}
                  {visibleIds.has('date') && (
                    <div className="text-start">
                      <span className="text-xs font-mono" style={{ color: 'var(--text-muted)' }}>
                        {fmtTs(endedAt(h))}
                      </span>
                    </div>
                  )}

                  {/* Duration */}
                  {visibleIds.has('duration') && (
                    <div className="text-start">
                      <span className="text-xs font-mono" style={{ color: dur ? 'var(--text-primary)' : 'var(--text-soft)' }}>
                        {dur ?? '—'}
                      </span>
                    </div>
                  )}

                  {/* Reserved for the header's settings gear — keeps labels over their own cells. */}
                  <div aria-hidden />
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Hover tooltip */}
      {hover && <HoverTooltip h={hover.h} t={t} rect={hover.rect} pendingMinutes={pendingMinutes} />}

      {/* Detail modal */}
      {detail && (
        <HandoffDetailModal
          h={detail}
          open={!!detail}
          onClose={() => setDetail(null)}
          phase={phaseOf(detail, pendingMinutes)}
          accent={cardView(detail, t, pendingMinutes).accent}
          t={t}
        />
      )}
    </>
  );
}
