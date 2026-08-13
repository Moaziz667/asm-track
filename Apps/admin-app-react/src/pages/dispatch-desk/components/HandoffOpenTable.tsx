import { useState, useRef, useCallback, useMemo, useEffect } from 'react';
import type { ReactNode } from 'react';
import type { TranslationSchema } from '@/lib/i18n/LocaleContext';
import { tlabel } from '@/lib/i18n/i18n-dict';
import { useT } from '@/lib/i18n/LocaleContext';
import { Link } from 'react-router-dom';
import { IconClock, IconArrowRight, IconX } from '@tabler/icons-react';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { DisplaySettingsDropdown } from '@/components/ui/DisplaySettingsDropdown';
import { useColumnSettings } from '@/hooks/useColumnSettings';
import { useDensity } from '@/hooks/useDensity';
import { useOpsSettings, HANDOFF_PENDING_KEY, HANDOFF_PENDING_DEFAULT, HANDOFF_AUTO_CANCEL_KEY, HANDOFF_AUTO_CANCEL_DEFAULT } from '@/hooks/useOpsSettings';
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

// Declaration order IS render order — see `activeColumns` below. `actions` stays last because its
// track doubles as the header's settings-gear slot, which keeps labels over their own cells.
const OPEN_COLUMNS: ColumnDef[] = [
  { id: 'status',   label: 'Statut',   pinned: true },
  { id: 'client',   label: 'Client',   pinned: true },
  { id: 'transfer', label: 'De → Vers' },
  { id: 'ref',      label: 'Réf' },
  { id: 'sla',      label: 'SLA' },
  { id: 'actions',  label: '',         pinned: true },
];

// The SLA track is fixed: a countdown that stretches with the viewport becomes the loudest thing
// on screen while carrying the least text. Everything readable takes the fluid space instead.
const COL_TRACKS: Record<string, string> = {
  status:   'minmax(140px, 1.1fr)',
  client:   'minmax(150px, 1.6fr)',
  transfer: 'minmax(230px, 2fr)',
  ref:      'minmax(110px, 1fr)',
  sla:      '176px',
  actions:  '44px',
};

const ROW_H: Record<string, number> = { compact: 40, comfortable: 56, spacious: 68 };

function fmtTs(iso?: string): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
}

// Warn at the last third, alarm at the last twelfth — proportional, so shortening the window to
// 12 minutes still leaves a usable amber phase instead of jumping straight from green to red.
function slaColor(elapsed: number, slaMs: number): string {
  const remaining = slaMs - elapsed;
  if (remaining <= slaMs / 12) return 'var(--danger)';
  if (remaining <= slaMs / 3) return 'var(--warning)';
  return 'var(--success)';
}

function slaLabel(elapsed: number, slaMs: number, expiredLabel: string): string {
  const remaining = Math.max(0, slaMs - elapsed);
  const totalSec = Math.floor(remaining / 1000);
  const m = Math.floor(totalSec / 60);
  const s = totalSec % 60;
  if (remaining <= 0) return expiredLabel;
  return `${m}:${String(s).padStart(2, '0')}`;
}

/** Live countdown to the tenant's auto-cancel deadline — updates every second, color-coded. */
function SlaCountdown({ requestedAt, slaMinutes }: { requestedAt?: string; slaMinutes: number }) {
  const t = useT();
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  if (!requestedAt) return <span style={{ color: 'var(--text-soft)' }}>—</span>;

  const slaMs = slaMinutes * 60 * 1000;
  const elapsed = now - new Date(requestedAt).getTime();
  const progress = Math.min(1, Math.max(0, elapsed / slaMs));
  const color = slaColor(elapsed, slaMs);
  const expiredLabel = (t.common?.expired as string) ?? 'Expiré';
  const label = slaLabel(elapsed, slaMs, expiredLabel);

  return (
    <div className="flex items-center gap-2">
      <div className="h-1 rounded-full overflow-hidden shrink-0" style={{ width: 96, background: 'var(--border)' }}>
        <div
          className="h-full rounded-full transition-[width] duration-1000"
          style={{ width: `${progress * 100}%`, background: color }}
        />
      </div>
      <span
        className="text-xs font-mono font-[600] shrink-0 tabular-nums"
        style={{ color, minWidth: 56, textAlign: 'end' }}
      >
        {label}
      </span>
    </div>
  );
}

/** The two drivers a parcel travels between — overlapping avatars, then the names in full. */
function CustodyPair({ h }: { h: HandoffItem }) {
  return (
    <div className="flex items-center gap-2 min-w-0">
      <span className="flex items-center shrink-0">
        <DriverAvatarById driverId={h.fromDriverId} name={h.fromDriverName} size={22} />
        <span
          className="flex items-center rounded-full"
          style={{ marginInlineStart: -7, boxShadow: '0 0 0 2px var(--surface)', borderRadius: '50%' }}
        >
          <DriverAvatarById driverId={h.toDriverId} name={h.toDriverName} size={22} />
        </span>
      </span>
      <span className="flex items-center gap-1.5 min-w-0 text-xs">
        <span className="truncate" style={{ color: 'var(--text-secondary)' }}>{h.fromDriverName ?? '—'}</span>
        <IconArrowRight size={12} stroke={2.5} className="shrink-0" style={{ color: 'var(--text-soft)' }} />
        <span className="truncate font-[600]" style={{ color: 'var(--text-primary)' }}>{h.toDriverName ?? '—'}</span>
      </span>
    </div>
  );
}

/** Floating tooltip shown on row hover — quick preview without clicking. */
function HoverTooltip({ h, t, rect, pendingMinutes }: { h: HandoffItem; t: TranslationSchema; rect: DOMRect; pendingMinutes: number }) {
  const view = cardView(h, t, pendingMinutes);
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
  // Tenant thresholds — the countdown must expire when the backend actually expires the handoff.
  const { getInt } = useOpsSettings();
  const pendingMinutes = getInt(HANDOFF_PENDING_KEY, HANDOFF_PENDING_DEFAULT);
  const autoCancelMinutes = getInt(HANDOFF_AUTO_CANCEL_KEY, HANDOFF_AUTO_CANCEL_DEFAULT);
  // v2: the stored order predates the status column, and a stale order would append it last.
  const { orderedColumns, visibleIds, toggleColumn, moveColumn, resetColumns } =
    useColumnSettings('handoff-open-v2', OPEN_COLUMNS);

  // One ordered list drives the track widths, the header labels AND the cells. Building the tracks
  // separately (pinned first) is what silently slid every cell into the wrong column.
  const activeColumns = useMemo(
    () => orderedColumns.filter(c => c.pinned || visibleIds.has(c.id)),
    [orderedColumns, visibleIds],
  );
  const gridCols = useMemo(
    () => activeColumns.map(c => COL_TRACKS[c.id] ?? '1fr').join(' '),
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

  const renderCell = useCallback((colId: string, h: HandoffItem): ReactNode => {
    const view = cardView(h, t, pendingMinutes);
    switch (colId) {
      case 'status':
        return (
          <span className="flex items-center gap-2 min-w-0">
            <span className="rounded-full shrink-0" style={{ width: 6, height: 6, background: view.accent }} aria-hidden />
            <span className="text-xs font-[600] truncate" style={{ color: view.accent }}>{view.label}</span>
          </span>
        );
      case 'client':
        return (
          <span className="flex flex-col min-w-0">
            <span className="text-sm font-[600] truncate" style={{ color: 'var(--text-primary)' }}>
              {h.clientName ?? '—'}
            </span>
            {density !== 'compact' && h.dropoffAddress && (
              <span className="text-2xs truncate" style={{ color: 'var(--text-muted)' }}>{h.dropoffAddress}</span>
            )}
          </span>
        );
      case 'transfer':
        return <CustodyPair h={h} />;
      case 'ref':
        return h.deliveryId ? (
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
        );
      case 'sla':
        return <SlaCountdown requestedAt={h.requestedAt} slaMinutes={autoCancelMinutes} />;
      case 'actions':
        return isReadOnly ? null : (
          <span className="flex items-center justify-end" onClick={(e) => e.stopPropagation()}>
            <button
              type="button"
              onClick={() => onCancel(h)}
              disabled={cancellingId === h.id}
              title={t.dispatchDeskPage.handoffCancelTitle}
              aria-label={t.dispatchDeskPage.handoffCancelTitle}
              className="w-7 h-7 rounded-[var(--radius)] flex items-center justify-center opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity disabled:opacity-50 hover:bg-[var(--danger-bg)]"
              style={{ color: 'var(--danger)' }}
            >
              <IconX size={14} stroke={2.5} />
            </button>
          </span>
        );
      default:
        return null;
    }
  }, [t, density, isReadOnly, cancellingId, onCancel, pendingMinutes, autoCancelMinutes]);

  if (items.length === 0) return null;

  return (
    <>
      <div className="flex-1 overflow-y-auto">
        <div className="rounded-[var(--radius-xl)] overflow-hidden" style={{ background: 'var(--surface)', border: '1px solid var(--border)', boxShadow: 'var(--shadow-card)' }} role="table">
          {/* Header — the settings gear lives in the `actions` track, so every label sits over its
              own cells instead of drifting by the gear's width. */}
          <div
            className="sticky top-0 z-10 grid items-center h-[44px] px-4 border-b border-[var(--border)]"
            style={{ gridTemplateColumns: gridCols, background: 'var(--surface-sunken)', boxShadow: 'var(--shadow-inset)' }}
            role="row"
          >
            {activeColumns.map(col => col.id === 'actions' ? (
              <div key={col.id} className="flex items-center justify-end">
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
            ) : (
              <span key={col.id} role="columnheader" className="text-xs font-semibold text-[var(--text-muted)] text-start truncate">
                {tlabel(t.dispatchDeskPage, `handoffCol${col.id.charAt(0).toUpperCase() + col.id.slice(1)}`) ?? col.label}
              </span>
            ))}
          </div>

          {items.map((h, idx) => {
            // Urgency reads as a tinted row, not as red text: three red rows in a column look like a
            // broken screen, while a tint still lets the countdown be the thing that shouts.
            const overdue = phaseOf(h, pendingMinutes) === 'overdue';
            return (
              <div
                key={h.id || idx}
                role="row"
                tabIndex={0}
                className="grid items-center cursor-pointer group hover:bg-[var(--hover-bg)] focus-visible:bg-[var(--hover-bg)] outline-none transition-colors px-4 border-b border-[var(--border)] last:border-b-0"
                style={{
                  gridTemplateColumns: gridCols,
                  height: ROW_H[density],
                  background: overdue ? 'var(--danger-bg)' : 'var(--surface)',
                }}
                onClick={() => setDetail(h)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setDetail(h); } }}
                onMouseEnter={(e) => onRowEnter(e, h)}
                onMouseLeave={onRowLeave}
              >
                {activeColumns.map(col => (
                  <div key={col.id} role="cell" className="min-w-0 text-start">
                    {renderCell(col.id, h)}
                  </div>
                ))}
              </div>
            );
          })}
        </div>
      </div>

      {hover && <HoverTooltip h={hover.h} t={t} rect={hover.rect} pendingMinutes={pendingMinutes} />}

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
