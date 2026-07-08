import { type ReactNode } from 'react';
import { IconCheck, IconChevronRight, IconTruck } from '@tabler/icons-react';
import { StatusBadge } from '@/components/data-display/StatusBadge';
import { FieldTextarea } from '@/components/ui/field';
import { DriverAvatarById } from '@/components/data-display/DriverAvatar';
import { DRIVER_STATUS_COLOR } from '@/lib/ui/design-tokens';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/i18n/LocaleContext';
import type { Driver } from '@/types';
import type { RouteData, NearestInfo, Cfg, ReassignTarget } from './types';
import { fmtEta, fmtKm, fmtSeen } from './helpers';

// ── Note field ──────────────────────────────────────────────────────────────
export function NoteField({ t, inField, note, setNote }: { t: ReturnType<typeof useT>; inField: boolean; note: string; setNote: (v: string) => void }) {
  return (
    <FieldTextarea
      label={`${t.reassignDrawer.noteForDriver} ${t.reassignDrawer.noteOptional}`}
      placeholder={inField ? t.reassignDrawer.notePlaceholder : t.reassignDrawer.noteInternalPlaceholder}
      value={note} onChange={e => setNote(e.currentTarget.value)} rows={2} wrapperClassName="mt-4"
    />
  );
}

// ── Batch stepper progress ────────────────────────────────────────────────────
export function BatchProgress({ targets, stepIndex, cfg, t }: { targets: ReassignTarget[]; stepIndex: number; cfg: Record<string, Cfg>; t: ReturnType<typeof useT> }) {
  return (
    <div className="mb-3">
      <div className="flex items-center justify-between mb-2">
        <span className="text-2xs text-[var(--text-secondary)]">{t.assignFlow.placeOneByOne}</span>
        <span className="text-2xs font-semibold text-[var(--text-primary)] tabular-nums">{stepIndex + 1} <span className="text-[var(--text-muted)] font-normal">/ {targets.length}</span></span>
      </div>
      <div className="flex gap-1">
        {targets.map((x, i) => {
          const done = i < stepIndex || (cfg[x.deliveryId]?.touched && i !== stepIndex);
          const cur = i === stepIndex;
          return <div key={x.deliveryId} className="flex-1 h-[3px] rounded-full" style={{ background: cur ? 'var(--brand)' : done ? 'var(--text-secondary)' : 'var(--border-strong)' }} />;
        })}
      </div>
      <div className="flex gap-x-3 gap-y-1 flex-wrap mt-2">
        {targets.map((x, i) => {
          const done = i < stepIndex || (cfg[x.deliveryId]?.touched && i !== stepIndex);
          const cur = i === stepIndex;
          const ref = x.orderRef || x.deliveryId.slice(0, 6).toUpperCase();
          return (
            <span key={x.deliveryId} className={cn('inline-flex items-center gap-1 text-2xs', cur ? 'font-semibold text-[var(--brand)]' : done ? 'text-[var(--text-muted)]' : 'text-[var(--text-soft)]')}>
              {done && <IconCheck size={12} />}{ref}
            </span>
          );
        })}
      </div>
    </div>
  );
}

// ── Phase 1 driver row ────────────────────────────────────────────────────────
export function DriverRow({ driver, hero = false, dimmed = false, hasRoute = false, summary, nearest, havKm, onSelect, t }: {
  driver: Driver; hero?: boolean; dimmed?: boolean; hasRoute?: boolean; summary?: RouteData | null;
  nearest: Record<string, NearestInfo>; havKm: (d: Driver) => number | null;
  onSelect: (id: string) => void; t: ReturnType<typeof useT>;
}) {
  const near = nearest[driver.id];
  const hav = havKm(driver);
  const proximity = [fmtEta(near?.etaSeconds), fmtKm(near?.distanceMeters) ?? (hav != null ? `${hav.toFixed(1)} km` : null)].filter(Boolean).join(' · ');
  const statusCfg = DRIVER_STATUS_COLOR[(driver.onlineStatus ?? 'OFFLINE') as keyof typeof DRIVER_STATUS_COLOR] ?? DRIVER_STATUS_COLOR.OFFLINE;
  const seen = fmtSeen(driver.lastLocationAt);
  return (
    <button type="button" onClick={() => onSelect(driver.id)}
      className={cn('w-full text-start rounded-lg p-2.5 flex items-center gap-2.5 border border-transparent hover:bg-[var(--hover-bg)] transition-colors mb-1')}
      style={{ opacity: dimmed ? 0.6 : 1, background: hero ? 'var(--surface-sunken)' : undefined }}>
      <span className="relative shrink-0">
        <DriverAvatarById driverId={driver.id} name={driver.name} size={hero ? 38 : 32} />
        <PresenceDot status={driver.onlineStatus} />
      </span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5">
          <p className={cn('truncate text-[var(--text-primary)]', hero ? 'text-sm font-bold' : 'text-sm font-semibold')}>{driver.name}</p>
          {proximity && <span className="ms-auto shrink-0 text-2xs font-semibold text-[var(--text-secondary)] tabular-nums">{proximity}</span>}
        </div>
        <div className="flex items-center gap-x-1.5 flex-wrap text-2xs mt-0.5">
          <span className="font-medium" style={{ color: statusCfg.text }}>{statusCfg.label}</span>
          {seen && <><span className="text-[var(--text-soft)]">·</span><span style={{ color: seen.fresh ? 'var(--success)' : 'var(--text-muted)' }}>{seen.text}</span></>}
          <span className="text-[var(--text-soft)]">·</span>
          {summary ? (
            <span className="inline-flex items-center gap-1 min-w-0">
              <span className="inline-flex items-center gap-0.5 text-[var(--text-muted)] truncate max-w-[80px]"><IconTruck size={10} />{summary.name}</span>
              <StatusBadge status={summary.status} size="sm" />
            </span>
          ) : (
            <span className="inline-flex items-center gap-0.5 text-[var(--text-muted)]"><IconTruck size={10} />{hasRoute ? t.reassignDrawer.routeInProgress : t.reassignDrawer.noRoutes}</span>
          )}
        </div>
      </div>
      <IconChevronRight size={14} className="shrink-0 text-[var(--text-soft)]" />
    </button>
  );
}

// Online/offline presence indicator, bottom-right of the driver avatar. A filled ring when active,
// a hollow ring when offline — the one dot we keep (it reads as presence, not decoration).
export function PresenceDot({ status, size = 10 }: { status?: Driver['onlineStatus']; size?: number }) {
  const cfg = DRIVER_STATUS_COLOR[(status ?? 'OFFLINE') as keyof typeof DRIVER_STATUS_COLOR] ?? DRIVER_STATUS_COLOR.OFFLINE;
  const offline = !status || status === 'OFFLINE';
  return (
    <span className="absolute -bottom-0.5 -end-0.5 rounded-full" aria-hidden
      style={{ width: size, height: size, background: offline ? 'var(--surface)' : cfg.dot, boxShadow: `0 0 0 2px var(--surface)${offline ? `, inset 0 0 0 1.5px ${cfg.dot}` : ''}` }} />
  );
}

export function Section({ label, icon, children }: { label: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <div className="mt-3 first:mt-0">
      <p className="text-2xs font-semibold uppercase tracking-wide text-[var(--text-muted)] mb-1 flex items-center gap-1">{icon}{label}</p>
      {children}
    </div>
  );
}
