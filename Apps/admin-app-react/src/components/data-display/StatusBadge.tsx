

import { useT } from '@/lib/LocaleContext';

export type StatusValue =
  | 'DRAFT' | 'VALIDATED' | 'IN_PROGRESS' | 'CLOSED' | 'CANCELLED'
  | 'FAILED' | 'SLA_BREACH'
  | 'UNSCHEDULED' | 'SCHEDULED' | 'PICKED_UP' | 'IN_TRANSIT'
  | 'DELIVERED' | 'PARTIALLY_DELIVERED' | 'COMPLETED' | 'PARTIAL'
  | 'REMOVED_REPLANNED' | 'REMOVED_CANCELLED' | 'FAILED_ATTEMPT';

interface StatusConfig {
  dot: string;
  bg: string;
  text: string;
}

// Linear philosophy: desaturated, cool-toned, never neon.
// dot = true color · text = same hue, slightly darkened · bg = 8% tint
const CONFIG: Record<string, StatusConfig> = {
  // ── Route statuses ────────────────────────────────────────────────────
  DRAFT:               { dot: '#8A8F98', bg: 'rgba(138,143,152,0.08)', text: '#6B7280' },
  VALIDATED:           { dot: '#5E6AD2', bg: 'rgba(94,106,210,0.09)',  text: '#4C56B8' },
  IN_PROGRESS:         { dot: '#5E6AD2', bg: 'rgba(94,106,210,0.09)',  text: '#4C56B8' },
  CLOSED:              { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  COMPLETED:           { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  CANCELLED:           { dot: '#8A8F98', bg: 'rgba(138,143,152,0.07)', text: '#6B7280' },
  FAILED:              { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24' },
  SLA_BREACH:          { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24' },
  // ── Delivery statuses ─────────────────────────────────────────────────
  UNSCHEDULED:         { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10' },
  SCHEDULED:           { dot: '#5E6AD2', bg: 'rgba(94,106,210,0.09)',  text: '#4C56B8' },
  PICKED_UP:           { dot: '#2594B8', bg: 'rgba(37,148,184,0.09)',  text: '#1A7A9A' },
  IN_TRANSIT:          { dot: '#D4772C', bg: 'rgba(212,119,44,0.09)',  text: '#B05A18' },
  DELIVERED:           { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  PARTIALLY_DELIVERED: { dot: '#7B6FCC', bg: 'rgba(123,111,204,0.09)', text: '#6055A8' },
  PARTIAL:             { dot: '#7B6FCC', bg: 'rgba(123,111,204,0.09)', text: '#6055A8' },
  // ── Stop removal statuses ─────────────────────────────────────────────
  REMOVED_REPLANNED:   { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10' },
  REMOVED_CANCELLED:   { dot: '#8A8F98', bg: 'rgba(138,143,152,0.07)', text: '#6B7280' },
  FAILED_ATTEMPT:      { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24' },
};

const PULSE_STATUSES = new Set(['IN_PROGRESS', 'IN_TRANSIT', 'PICKED_UP']);

interface StatusBadgeProps {
  status: StatusValue | string;
  label?: string;
  size?: 'sm' | 'md';
  pulse?: boolean;
}

export function StatusBadge({ status, label, size = 'md', pulse }: StatusBadgeProps) {
  const t = useT();
  const cfg = CONFIG[status] ?? { dot: '#A1A1AA', bg: 'rgba(161,161,170,0.10)', text: '#71717A' };
  const shouldPulse = pulse ?? PULSE_STATUSES.has(status);
  const displayLabel = label ?? (t.statusLabels as any)[status] ?? status;

  const dotPx = size === 'sm' ? 5 : 5.5;
  const fontSize = size === 'sm' ? 11 : 11;
  const height = size === 'sm' ? 18 : 20;
  const px = size === 'sm' ? 7 : 8;
  const gap = 5;

  return (
    <span
      role="status"
      aria-label={displayLabel}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap,
        height,
        padding: `0 ${px}px`,
        borderRadius: 99,
        background: cfg.bg,
        flexShrink: 0,
      }}
    >
      <span
        style={{
          width: dotPx,
          height: dotPx,
          borderRadius: '50%',
          background: cfg.dot,
          flexShrink: 0,
          ...(shouldPulse ? { animation: 'asm-pulse 2s ease-in-out infinite' } : {}),
        }}
      />
      <span
        style={{
          fontFamily: "'Amazon Ember', 'Inter', system-ui, sans-serif",
          fontSize,
          fontWeight: 500,
          color: cfg.text,
          letterSpacing: '-0.01em',
          lineHeight: 1,
          whiteSpace: 'nowrap',
        }}
      >
        {displayLabel}
      </span>
      {shouldPulse && (
        <style>{`
          @keyframes asm-pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50%       { opacity: 0.4; transform: scale(0.75); }
          }
        `}</style>
      )}
    </span>
  );
}

