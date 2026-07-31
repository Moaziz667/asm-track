import { useT } from '@/lib/i18n/LocaleContext';
import { ageParts, type AgeParts } from '@/lib/health/system-health';

// ── Tone → design tokens ──────────────────────────────────────────────────────
// The page reads as prose, so a tone is only ever a small dot next to a word. There is no tinted
// banner, no badge and no filled button: nothing on this page should shout before it is read.
export type Tone = 'ok' | 'warn' | 'down' | 'idle';

export const TONE_VAR: Record<Tone, string> = {
  ok: 'var(--success)', warn: 'var(--warning)', down: 'var(--danger)', idle: 'var(--text-soft)',
};

export const groupTone = (g: { reachableDown: boolean; state: string }): Tone =>
  g.reachableDown || g.state === 'OPEN' || g.state === 'FORCED_OPEN' ? 'down'
  : g.state === 'HALF_OPEN' ? 'warn'
  : g.state === 'DISABLED' ? 'idle'
  : 'ok';

export interface CircuitBreaker {
  name: string; state: string; reachable?: boolean; shallow?: boolean;
  failureRate: number; bufferedCalls: number; failedCalls: number; notPermittedCalls: number;
}

/** Past this, a daily backup has plainly missed its slot — one skipped night is already a miss. */
const BACKUP_STALE_AFTER_MS = 36 * 60 * 60 * 1000;

export interface BackupStatus {
  status?: string; finishedAt?: string; detail?: string; offsite?: boolean;
}

/**
 * Turns the backup state file into a tone and a word.
 *
 * The failure worth catching is the quiet one: a job that stopped running weeks ago leaves a
 * state file still saying "ok". So freshness is judged here rather than trusted from the file —
 * an old success is reported as stale, never as green.
 */
export function describeBackup(b: BackupStatus | undefined, now: number, t: ReturnType<typeof useT>) {
  const dd = t.systemHealthPage;
  const offsite = b?.offsite === true;
  const age = b?.finishedAt ? formatAge(b.finishedAt, t) : '—';

  if (!b?.status || b.status === 'unknown') {
    return { tone: 'idle' as Tone, label: dd.backupUnknown, age: '—', offsite };
  }
  if (b.status !== 'ok') {
    return { tone: 'down' as Tone, label: dd.backupFailed, age, offsite };
  }
  const stale = b.finishedAt == null || now - Date.parse(b.finishedAt) > BACKUP_STALE_AFTER_MS;
  return {
    tone: stale ? ('warn' as Tone) : ('ok' as Tone),
    label: stale ? dd.backupStale : dd.backupOk,
    age, offsite,
  };
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
