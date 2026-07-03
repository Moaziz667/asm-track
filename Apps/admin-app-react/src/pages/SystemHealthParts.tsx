import React from 'react';
import {
  IconCircleCheck, IconChevronDown, IconReload, IconWifiOff, IconInfoCircle,
  IconClock, IconCircleX, IconCircleMinus,
} from '@tabler/icons-react';
import { cn } from '@/lib/utils';
import { useT } from '@/lib/LocaleContext';
import { ageParts, describeKey, type AgeParts, type DescriptionKey } from '@/lib/system-health';

// ── Tone → design tokens (no hardcoded hex; status = icon + label + tone, never a bare dot) ──
export type Tone = 'ok' | 'warn' | 'down' | 'idle';

export const TONE_VAR: Record<Tone, string> = {
  ok: 'var(--success)', warn: 'var(--warning)', down: 'var(--danger)', idle: 'var(--text-soft)',
};
export const TONE_BG: Record<Tone, string> = {
  ok: 'var(--success-bg)', warn: 'var(--warning-bg)', down: 'var(--danger-bg)', idle: 'var(--hover-bg)',
};
export const TONE_ICON: Record<Tone, typeof IconCircleCheck> = {
  ok: IconCircleCheck, warn: IconClock, down: IconCircleX, idle: IconCircleMinus,
};

export const toneRank = (t: Tone) => (t === 'down' ? 3 : t === 'warn' ? 2 : t === 'ok' ? 1 : 0);
export const worstTone = (tones: Tone[]): Tone =>
  tones.reduce<Tone>((acc, t) => (toneRank(t) > toneRank(acc) ? t : acc), 'ok');
export const groupTone = (g: { reachableDown: boolean; state: string }): Tone =>
  g.reachableDown || g.state === 'OPEN' || g.state === 'FORCED_OPEN' ? 'down'
  : g.state === 'HALF_OPEN' ? 'warn'
  : g.state === 'DISABLED' ? 'idle'
  : 'ok';

export const RANGES: { min: number; label: string }[] = [
  { min: 10, label: '10 min' }, { min: 30, label: '30 min' }, { min: 60, label: '1 h' },
];
export const MAX_SEGMENTS = 48;

export interface CircuitBreaker {
  name: string; state: string; reachable?: boolean; shallow?: boolean;
  failureRate: number; bufferedCalls: number; failedCalls: number; notPermittedCalls: number;
}

export interface ComponentRowData {
  kind: 'group' | 'db' | 'queues';
  label: string;
  icon: typeof IconCircleCheck;
  lane: 'drivers' | 'erp' | 'db' | 'queues' | null;
  tone: Tone;
  metric?: { value: string; tone: Tone };
  breakers?: CircuitBreaker[];
  dlqEntries?: [string, number][];
  dbReachable?: boolean;
}

// ── Hero status banner (full-width, tone-tinted; the single most legible element) ──
export function HeroBanner({ tone, icon: Icon, title, sub, chips, range }: {
  tone: Tone; icon: typeof IconCircleCheck; title: string; sub?: string;
  chips?: { label: string; hint?: string }[]; range?: string;
}) {
  return (
    <section className="rounded-[var(--radius)] border border-[var(--border)] px-5 py-4 flex items-center gap-4 flex-wrap bg-[var(--surface)]">
      <span className="w-10 h-10 rounded-[var(--radius)] flex items-center justify-center shrink-0"
        style={{ background: TONE_BG[tone], color: TONE_VAR[tone] }}>
        <Icon size={22} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-lg font-bold leading-tight" style={{ color: 'var(--text-primary)' }}>{title}</p>
        {sub && <p className="text-sm mt-0.5" style={{ color: 'var(--text-secondary)' }}>{sub}</p>}
      </div>
      {chips && chips.length > 0 && (
        <div className="flex items-center gap-2">
          {chips.map((c, i) => (
            <span key={i} title={c.hint} className="inline-flex items-baseline gap-1 px-2.5 py-1 rounded-full text-xs font-[600]"
              style={{ background: 'var(--surface-sunken)', color: 'var(--text-secondary)', border: '1px solid var(--border)' }}>
              <span className="font-mono tabular-nums" style={{ color: TONE_VAR[tone] }}>{c.label}</span>
            </span>
          ))}
        </div>
      )}
      {range && <span className="text-2xs hidden lg:inline" style={{ color: 'var(--text-soft)' }}>fenêtre · {range}</span>}
    </section>
  );
}

// ── Inline 60-min tone bar (per-component health timeline; data viz, not decoration) ──
export function HealthBar({ points, rangeLabel }: { points: Tone[]; rangeLabel?: string }) {
  const segs: Tone[] = [];
  if (points.length > 0) {
    const size = Math.max(1, Math.ceil(points.length / MAX_SEGMENTS));
    for (let i = 0; i < points.length; i += size) segs.push(worstTone(points.slice(i, i + size)));
  }
  if (segs.length === 0) {
    return <div className="flex-1 h-3 rounded-[2px]" style={{ background: 'var(--hover-bg)' }} title={rangeLabel ? `${rangeLabel} · —` : '—'} />;
  }
  const okPct = points.length ? Math.round((100 * points.filter(p => p === 'ok').length) / points.length) : 100;
  return (
    <div className="flex-1 flex gap-[2px] min-w-0 h-3" title={rangeLabel ? `${rangeLabel} · ${okPct}% ok` : `${okPct}% ok`}>
      {segs.map((s, i) => (
        <div key={i} className="flex-1 rounded-[2px]" style={{ background: TONE_VAR[s], opacity: s === 'ok' ? 0.45 : 1 }} />
      ))}
    </div>
  );
}

// ── One component row: chip · label · inline tone bar · status (icon+label) · metric · expand ──
export function ComponentRow({ row, barPoints, rangeLabel, isOpen, onToggle, onInfo, toneLabel, statusFor, getFriendlyQueue, dbOk, dbDown, infoLabel, thBreaker, thState, thRate, thFb }: {
  row: ComponentRowData; barPoints: Tone[]; rangeLabel?: string; isOpen: boolean; onToggle: () => void;
  onInfo: (k: DescriptionKey) => void; toneLabel: (t: Tone) => string;
  statusFor: (s: string) => { label: string; tone: Tone }; getFriendlyQueue: (q: string) => string;
  dbOk: string; dbDown: string; infoLabel: string; thBreaker: string; thState: string; thRate: string; thFb: string;
}) {
  const StatusIcon = TONE_ICON[row.tone];
  const hasDetails = row.kind === 'group'
    ? (row.breakers?.length ?? 0) > 0
    : row.kind === 'db' ? true : (row.dlqEntries?.length ?? 0) > 0;
  return (
    <div>
      <button
        type="button"
        onClick={onToggle}
        disabled={!hasDetails}
        className={cn('w-full flex items-center gap-3 px-4 py-3 text-start transition-colors', hasDetails && 'hover:bg-[var(--hover-bg)] cursor-pointer')}
      >
        <span className="w-8 h-8 rounded-[var(--radius)] flex items-center justify-center shrink-0" style={{ background: 'var(--hover-bg)', color: 'var(--text-secondary)' }}>
          <row.icon size={16} />
        </span>
        <div className="min-w-0 flex-1 md:flex-none md:w-44">
          <p className="text-sm font-[600] truncate" style={{ color: 'var(--text-primary)' }}>{row.label}</p>
        </div>
        <div className="hidden md:flex flex-1 min-w-0 items-center">
          <HealthBar points={barPoints} rangeLabel={rangeLabel} />
        </div>
        <span className="shrink-0 inline-flex items-center gap-1 text-xs font-[600]" style={{ color: TONE_VAR[row.tone] }}>
          <StatusIcon size={13} /><span>{toneLabel(row.tone)}</span>
        </span>
        {row.metric && (
          <span className="hidden lg:inline shrink-0 font-mono text-sm tabular-nums" style={{ color: TONE_VAR[row.metric.tone] }}>{row.metric.value}</span>
        )}
        <IconChevronDown size={15} className={cn('shrink-0 transition-transform', isOpen && 'rotate-180')} style={{ color: 'var(--text-soft)', visibility: hasDetails ? 'visible' : 'hidden' }} />
      </button>

      {isOpen && hasDetails && (
        <div className="px-4 pb-3 pt-2 border-t border-[var(--border)] bg-[var(--surface-sunken)]">
          {row.kind === 'group' && (
            <div className="overflow-x-auto">
              <table className="w-full text-xs min-w-[520px]">
                <thead>
                  <tr className="text-2xs uppercase tracking-wider" style={{ color: 'var(--text-muted)' }}>
                    <th className="text-start font-bold py-1.5">{thBreaker}</th>
                    <th className="text-start font-bold py-1.5">{thState}</th>
                    <th className="text-end font-bold py-1.5">{thRate}</th>
                    <th className="text-end font-bold py-1.5">{thFb}</th>
                  </tr>
                </thead>
                <tbody>
                  {row.breakers!.map(cb => {
                    const s = statusFor(cb.state);
                    return (
                      <tr key={cb.name} className="border-t border-[var(--border)]">
                        <td className="py-1.5 font-mono" style={{ color: 'var(--text-secondary)' }}>
                          <span className="inline-flex items-center gap-1.5">
                            <InfoButton onClick={() => onInfo(describeKey(cb.name, false))} label={infoLabel} />
                            <span className="truncate">{cb.name}{cb.reachable === false ? ' ⚠' : ''}</span>
                          </span>
                        </td>
                        <td className="py-1.5 font-mono" style={{ color: TONE_VAR[s.tone] }}>{cb.state}</td>
                        <td className="py-1.5 text-end tabular-nums">{cb.failureRate < 0 ? '—' : `${cb.failureRate.toFixed(0)}%`}</td>
                        <td className="py-1.5 text-end tabular-nums">{cb.failedCalls} / {cb.bufferedCalls}</td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
          {row.kind === 'db' && (
            <p className="text-sm py-1" style={{ color: row.dbReachable ? 'var(--success)' : 'var(--danger)' }}>
              {row.dbReachable ? dbOk : dbDown}
            </p>
          )}
          {row.kind === 'queues' && (
            <div className="flex flex-col">
              {row.dlqEntries!.map(([q, d]) => (
                <div key={q} className="flex items-center justify-between gap-3 py-1.5 border-t border-[var(--border)] first:border-t-0">
                  <span className="inline-flex items-center gap-1.5 min-w-0 font-mono text-xs" style={{ color: 'var(--text-secondary)' }}>
                    <InfoButton onClick={() => onInfo(describeKey(q, true))} label={infoLabel} />
                    <span className="truncate">{getFriendlyQueue(q)}</span>
                  </span>
                  <span className="font-mono text-xs tabular-nums shrink-0" style={{ color: Number(d) > 0 ? 'var(--danger)' : 'var(--text-soft)' }}>{d}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export function ActionRow({ icon: Icon, title, meta, detail, action }: {
  icon: any; title: string; meta: string; detail?: string; action: React.ReactNode;
}) {
  return (
    <div className="flex items-start gap-3 px-4 py-3 border-t border-[var(--border)] first:border-t-0 bg-[var(--surface)]">
      <span className="w-7 h-7 rounded-[var(--radius)] flex items-center justify-center shrink-0" style={{ background: 'var(--danger-bg)', color: 'var(--danger)' }}>
        <Icon size={15} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-[600] truncate" style={{ color: 'var(--text-primary)' }}>{title}</p>
        <p className="text-xs" style={{ color: 'var(--text-muted)' }}>{meta}</p>
        {detail && <p className="text-2xs mt-0.5 line-clamp-2 break-words" style={{ color: 'var(--danger)' }}>{detail}</p>}
      </div>
      <div className="shrink-0">{action}</div>
    </div>
  );
}

export function ActionButton({ onClick, busy, disabled, label }: { onClick: () => void; busy: boolean; disabled?: boolean; label: string }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className="text-xs font-[600] px-3 h-7 rounded-[var(--radius)] border border-[var(--brand)] text-[var(--brand)] hover:bg-[var(--brand)] hover:text-white transition-colors inline-flex items-center gap-1.5 disabled:opacity-50"
    >
      <IconReload size={13} className={busy ? 'animate-spin' : ''} />
      {label}
    </button>
  );
}

export function Banner({ tone, icon: Icon, title, sub }: { tone: Tone; icon: any; title: string; sub: string }) {
  return (
    <div className="rounded-[var(--radius-xl)] px-4 py-3 flex items-center gap-3 border"
      style={{ background: TONE_BG[tone], borderColor: `color-mix(in srgb, ${TONE_VAR[tone]} 35%, transparent)` }}>
      <Icon size={18} style={{ color: TONE_VAR[tone] }} />
      <div>
        <p className="text-sm font-bold" style={{ color: 'var(--text-primary)' }}>{title}</p>
        <p className="text-xs" style={{ color: 'var(--text-secondary)' }}>{sub}</p>
      </div>
    </div>
  );
}

export function InfoButton({ onClick, label }: { onClick: () => void; label: string }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      className="shrink-0 w-5 h-5 inline-flex items-center justify-center rounded-full text-[var(--text-muted)] hover:text-[var(--brand)] hover:bg-[var(--hover-bg)] transition-colors"
    >
      <IconInfoCircle size={14} />
    </button>
  );
}

export function SkeletonRow() {
  return (
    <div className="flex items-center gap-3 px-4 py-3">
      <span className="w-8 h-8 rounded-[var(--radius)] shrink-0" style={{ background: 'var(--hover-bg)' }} />
      <span className="h-3.5 rounded shrink-0" style={{ background: 'var(--hover-bg)', width: 120 }} />
      <span className="hidden md:block flex-1 h-3 rounded-[2px]" style={{ background: 'var(--hover-bg)' }} />
      <span className="h-3 rounded shrink-0" style={{ background: 'var(--hover-bg)', width: 64 }} />
      <span className="w-4 h-4 shrink-0" style={{ background: 'var(--hover-bg)', borderRadius: 2 }} />
    </div>
  );
}

export function formatAge(iso: string | null, t: ReturnType<typeof useT>): string {
  const parts: AgeParts = ageParts(iso, Date.now());
  const dd = t.systemHealthPage;
  switch (parts.kind) {
    case 'none': return '—';
    case 'minutes': return dd.durationMinutes.replace('{n}', String(parts.n));
    case 'hours': return dd.durationHours.replace('{h}', String(parts.h)).replace('{m}', String(parts.m));
    case 'days': return dd.durationDays.replace('{d}', String(parts.d)).replace('{h}', String(parts.h));
  }
}
