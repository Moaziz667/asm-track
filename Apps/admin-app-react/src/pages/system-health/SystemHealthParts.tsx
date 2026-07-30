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
