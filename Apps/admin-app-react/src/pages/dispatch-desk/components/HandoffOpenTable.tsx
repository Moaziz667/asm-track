import { useState, useRef, useCallback, useMemo, useEffect } from 'react';
import type { TranslationSchema } from '@/lib/LocaleContext';
import { tlabel } from '@/lib/i18n-dict';
import { Link } from 'react-router-dom';
import { IconClock, IconArrowRight, IconX } from '@tabler/icons-react';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import { useDensity } from '@/hooks/useDensity';
import { cardView, phaseOf } from './HandoffCards';
import { HandoffDetailModal } from './HandoffDetailModal';
import type { HandoffItem } from '../types';
import type { ColumnDef } from '@/hooks/useColumnSettings';

interface Props {
  items: HandoffItem[];
  t: TranslationSchema;
  isReadOnly: boolean;
  cancellingId: string | null;
  onCancel: (h: HandoffItem) => void;
}

const OPEN_COLUMNS: ColumnDef[] = [
  { id: 'client',   label: 'Client',   pinned: true },
  { id: 'transfer', label: 'De → Vers' },
  { id: 'ref',      label: 'Réf' },
  { id: 'sla',      label: 'SLA' },
  { id: 'actions',  label: '',  pinned: true },
];

const COL_TRACKS: Record<string, string> = {
  client:   'minmax(120px, 1.4fr)',
  transfer: 'minmax(200px, 2fr)',
  ref:      'minmax(120px, 1fr)',
  sla:      'minmax(160px, 1.4fr)',
  actions:  '48px',
};

const ROW_H: Record<string, number> = { compact: 40, comfortable: 52, spacious: 64 };

const SLA_MS = 60 * 60 * 1000; // 60 min auto-cancel

function fmtTs(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function slaColor(elapsed: number): string {
  const remaining = SLA_MS - elapsed;
  if (remaining <= 0) return 'var(--danger)';
  if (remaining <= 5 * 60 * 1000) return 'var(--danger)';
  if (remaining <= 20 * 60 * 1000) return 'var(--warning)';
  return 'var(--success)';
}

function slaLabel(elapsed: number): string {
  const remaining = Math.max(0, SLA_MS - elapsed);
  const totalSec = Math.floor(remaining / 1000);
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  if (remaining <= 0) return 'Expiré';
  return `${m}:${String(s).padStart(2, '0')}`;
}

/** Live SLA countdown — updates every second, color-coded. */
function SlaCountdown({ requestedAt }: { requestedAt?: string }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  if (!requestedAt) return <span style={{ color: 'var(--text-soft)' }}>—</span>;

  const elapsed = now - new Date(requestedAt).getTime();
  const progress = Math.min(1, Math.max(0, elapsed / SLA_MS));
  const color = slaColor(elapsed);
  const label = slaLabel(elapsed);

  return (
    <div className="flex items-center gap-2 min-w-0">
      {/* Progress bar */}
      <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--border)' }}>
        <div
          className="h-full rounded-full transition-[width] duration-1000"
          style={{ width: `${progress * 100}%`, background: color }}
        />
      </div>
      {/* Countdown */}
      <span
        className="text-2xs font-mono font-[600] shrink-0 tabular-nums"
        style={{ color, minWidth: 48, textAlign: 'right' }}
      >
        {label}
      </span>
    </div>
  );
}

/** Floating tooltip shown on row hover — quick preview without clicking. */
function HoverTooltip({ h, t, rect }: { h: HandoffItem; t: TranslationSchema; rect: DOMRect }) {
  const view = cardView(h, t);
  const byLabel = t.dispatchDeskPage.handoffByLabel ?? 'par';

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
          {fmtTs(h.requestedAt)}
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

export function HandoffOpenTable({ items, t, isReadOnly, cancellingId, onCancel }: Props) {
  const [detail, setDetail] = useState<HandoffItem | null>(null);
  const [hover, setHover] = useState<{ h: HandoffItem; rect: DOMRect } | null>(null);
  const hoverTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  const { density, setDensity } = useDensity('handoff-open', 'comfortable');
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } =
    useColumnSettings('handoff-open', OPEN_COLUMNS);

  const gridCols = useMemo(() => {
    const pinned = orderedColumns.filter(c => c.pinned);
    const visible = orderedColumns.filter(c => !c.pinned && visibleIds.has(c.id));
    const tracks = [...pinned, ...visible].map(c => COL_TRACKS[c.id] ?? '1fr');
    return tracks.join(' ');
  }, [orderedColumns, visibleIds]);

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
            className="sticky top-0 z-10 flex items-center h-[44px] px-4 border-b border-[var(--border)]"
            style={{ background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}
          >
            <div className="grid flex-1 items-center" style={{ gridTemplateColumns: gridCols }}>
              {orderedColumns.filter(c => c.pinned || visibleIds.has(c.id)).map(col => (
                <span key={col.id} className="text-xs font-semibold text-[var(--text-muted)] text-start">
                  {tlabel(t.dispatchDeskPage, `handoffCol${col.id.charAt(0).toUpperCase() + col.id.slice(1)}`) ?? col.label}
                </span>
              ))}
            </div>
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

          {/* Rows */}
          {items.map((h, idx) => {
            const view = cardView(h, t);
            return (
              <div key={h.id || idx} className="border-b border-[var(--border)]" style={{ background: 'var(--surface)' }}>
                <div
                  className="grid items-center cursor-pointer group hover:bg-[var(--hover-bg)] transition-colors px-4"
                  style={{ gridTemplateColumns: gridCols, height: ROW_H[density] }}
                  onClick={() => setDetail(h)}
                  onMouseEnter={(e) => onRowEnter(e, h)}
                  onMouseLeave={onRowLeave}
                >
                  {/* Client (pinned) */}
                  <div className="text-start min-w-0">
                    <span className="text-xs font-[600] truncate block" style={{ color: 'var(--text-primary)' }}>
                      {h.clientName ?? '—'}
                    </span>
                  </div>

                  {/* Transfer */}
                  {visibleIds.has('transfer') && (
                    <div className="flex items-center gap-1.5 min-w-0 text-start">
                      <DriverAvatarById driverId={h.fromDriverId} name={h.fromDriverName} size={18} />
                      <span className="text-2xs truncate max-w-[72px]" style={{ color: 'var(--text-secondary)' }}>
                        {h.fromDriverName ?? '—'}
                      </span>
                      <IconArrowRight size={10} stroke={2.5} className="shrink-0" style={{ color: 'var(--text-soft)' }} />
                      <span className="text-2xs font-[600] truncate max-w-[72px]" style={{ color: 'var(--text-primary)' }}>
                        {h.toDriverName ?? '—'}
                      </span>
                      <DriverAvatarById driverId={h.toDriverId} name={h.toDriverName} size={18} />
                    </div>
                  )}

                  {/* Ref */}
                  {visibleIds.has('ref') && (
                    <div className="text-start">
                      {h.deliveryId ? (
                        <Link
                          to={`/deliveries/${h.deliveryId}`}
                          onClick={(e) => e.stopPropagation()}
                          className="font-mono text-2xs font-[600] hover:underline"
                          style={{ color: 'var(--brand)' }}
                        >
                          {h.erpOrderId || h.deliveryId.slice(0, 8)}
                        </Link>
                      ) : (
                        <span className="font-mono text-2xs font-[600]" style={{ color: 'var(--text-soft)' }}>—</span>
                      )}
                    </div>
                  )}

                  {/* SLA countdown */}
                  {visibleIds.has('sla') && (
                    <div className="text-start min-w-0">
                      <SlaCountdown requestedAt={h.requestedAt} />
                    </div>
                  )}

                  {/* Actions */}
                  {visibleIds.has('actions') && !isReadOnly && (
                    <div className="flex items-center justify-end" onClick={(e) => e.stopPropagation()}>
                      <button
                        type="button"
                        onClick={() => onCancel(h)}
                        disabled={cancellingId === h.id}
                        className="text-2xs font-[600] h-6 px-2 rounded-[var(--radius)] flex items-center gap-1 transition-colors disabled:opacity-50 hover:bg-[var(--danger-bg)]"
                        style={{ color: 'var(--danger)' }}
                      >
                        <IconX size={12} stroke={2.5} />
                      </button>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Hover tooltip */}
      {hover && <HoverTooltip h={hover.h} t={t} rect={hover.rect} />}

      {/* Detail modal */}
      {detail && (
        <HandoffDetailModal
          h={detail}
          open={!!detail}
          onClose={() => setDetail(null)}
          phase={phaseOf(detail)}
          accent={cardView(detail, t).accent}
          t={t}
        />
      )}
    </>
  );
}
