

import { useT } from '@/lib/LocaleContext';
import {
  IconPencil, IconChecks, IconPlayerPlay, IconCircleCheck, IconCircleMinus, IconBan, IconCircleX,
  IconAlertTriangle, IconCalendarOff, IconCalendarCheck, IconPackage, IconTruckDelivery,
  IconPackages, IconArrowBackUp, IconClock, IconPackageImport, IconBuildingWarehouse,
  IconRefresh, IconCircleOff, IconTruck, IconUserOff, IconMapPinOff,
  IconPackageOff, IconDots, IconAlertOctagon, IconHistory, IconArrowsExchange,
} from '@tabler/icons-react';

export type StatusValue =
  | 'DRAFT' | 'VALIDATED' | 'IN_PROGRESS' | 'CLOSED' | 'CANCELLED'
  | 'FAILED' | 'SLA_BREACH'
  | 'UNSCHEDULED' | 'SCHEDULED' | 'PICKED_UP' | 'IN_TRANSIT'
  | 'DELIVERED' | 'PARTIALLY_DELIVERED' | 'COMPLETED' | 'PARTIAL'
  | 'REMOVED_REPLANNED' | 'REMOVED_CANCELLED' | 'FAILED_ATTEMPT'
  | 'BACKORDER' | 'RESCHEDULED' | 'REASSIGNED';

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
  // ── Returns (RMA) statuses ────────────────────────────────────────────
  REQUESTED:           { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10' },
  APPROVED:            { dot: '#5E6AD2', bg: 'rgba(94,106,210,0.09)',  text: '#4C56B8' },
  RECEIVED:            { dot: '#2594B8', bg: 'rgba(37,148,184,0.09)',  text: '#1A7A9A' },
  RESTOCKED:           { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  REJECTED:            { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24' },
  // ── ERP sync statuses ─────────────────────────────────────────────────
  SYNCED:              { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  SYNCING:             { dot: '#2594B8', bg: 'rgba(37,148,184,0.09)',  text: '#1A7A9A' },
  PENDING:             { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10' },
  SYNC_FAILED:         { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24' },
  NOT_SYNCED:          { dot: '#8A8F98', bg: 'rgba(138,143,152,0.07)', text: '#6B7280' },
  // ── Delivery modifiers ────────────────────────────────────────────────
  BACKORDER:           { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10' },
  RESCHEDULED:         { dot: '#2594B8', bg: 'rgba(37,148,184,0.09)',  text: '#1A7A9A' },
  REASSIGNED:          { dot: '#2594B8', bg: 'rgba(37,148,184,0.09)',  text: '#1A7A9A' },
  // ── Generic active/inactive (failure reasons, toggles…) ───────────────
  ACTIVE:              { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  INACTIVE:            { dot: '#8A8F98', bg: 'rgba(138,143,152,0.07)', text: '#6B7280' },
  // ── Vehicle statuses ──────────────────────────────────────────────────
  AVAILABLE:           { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  ENGAGED:             { dot: '#5E6AD2', bg: 'rgba(94,106,210,0.09)',  text: '#4C56B8' },
  RETIRED:             { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24' },
  // ── Failure categories ────────────────────────────────────────────────
  CLIENT_ABSENT:       { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10' },
  REFUSED:             { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24' },
  WRONG_ADDRESS:       { dot: '#D4772C', bg: 'rgba(212,119,44,0.09)',  text: '#B05A18' },
  DAMAGED:             { dot: '#7B6FCC', bg: 'rgba(123,111,204,0.09)', text: '#6055A8' },
  MISSING:             { dot: '#C4881A', bg: 'rgba(196,136,26,0.09)',  text: '#A06D10' },
  OTHER:               { dot: '#8A8F98', bg: 'rgba(138,143,152,0.07)', text: '#6B7280' },
  // ── Failure contexts (S'applique à) — unique tones, distinct from categories ──
  FAILURE:             { dot: '#64748B', bg: 'rgba(100,116,139,0.09)', text: '#475569' },
  ITEM_REFUSED:        { dot: '#BE4963', bg: 'rgba(190,73,99,0.09)',   text: '#9C3651' },
  ITEM_DAMAGED:        { dot: '#2594B8', bg: 'rgba(37,148,184,0.09)',  text: '#1A7A9A' },
  ITEM_MISSING:        { dot: '#7D8B2A', bg: 'rgba(125,139,42,0.09)',  text: '#657220' },
  // ── SLA health (timeline + settings legend) — tones MUST mirror SlaTimeline's TONE ──
  ON_TRACK:            { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  MET:                 { dot: '#4CAF82', bg: 'rgba(76,175,130,0.09)',  text: '#2D8A5E' },
  AT_RISK:             { dot: '#D4772C', bg: 'rgba(212,119,44,0.09)',  text: '#B05A18' },
  LATE:                { dot: '#D4772C', bg: 'rgba(212,119,44,0.09)',  text: '#B05A18' },
  BREACHED:            { dot: '#C7372F', bg: 'rgba(199,55,47,0.09)',   text: '#A52B24' },
};

// Per-status glyph (icon + label + tone — never a bare dot; see .ai/anti-slop.md). Icons may repeat
// across kindred states (all "success" terminals = a check, all "rejected/failed" = a cross); the tone
// and label disambiguate. Any status without an entry falls back to the legacy dot below.
const ICON: Record<string, typeof IconCircleCheck> = {
  // Route
  DRAFT: IconPencil, VALIDATED: IconChecks, IN_PROGRESS: IconPlayerPlay,
  CLOSED: IconCircleCheck, COMPLETED: IconCircleCheck, CANCELLED: IconBan,
  FAILED: IconCircleX, SLA_BREACH: IconAlertTriangle,
  // Delivery
  UNSCHEDULED: IconCalendarOff, SCHEDULED: IconCalendarCheck, PICKED_UP: IconPackage,
  IN_TRANSIT: IconTruckDelivery, DELIVERED: IconCircleCheck,
  PARTIALLY_DELIVERED: IconPackages, PARTIAL: IconPackages,
  // Stop removal
  REMOVED_REPLANNED: IconArrowBackUp, REMOVED_CANCELLED: IconBan, FAILED_ATTEMPT: IconAlertTriangle,
  // Returns (RMA)
  REQUESTED: IconClock, APPROVED: IconCircleCheck, RECEIVED: IconPackageImport,
  RESTOCKED: IconBuildingWarehouse, REJECTED: IconCircleX,
  // ERP sync
  SYNCED: IconCircleCheck, SYNCING: IconRefresh, PENDING: IconClock,
  SYNC_FAILED: IconAlertTriangle, NOT_SYNCED: IconCircleOff,
  // Delivery modifiers
  BACKORDER: IconArrowBackUp, RESCHEDULED: IconHistory, REASSIGNED: IconArrowsExchange,
  // Generic active/inactive
  ACTIVE: IconCircleCheck, INACTIVE: IconCircleMinus,
  // Vehicle
  AVAILABLE: IconCircleCheck, ENGAGED: IconTruck, RETIRED: IconCircleX,
  // Failure categories / item outcomes
  CLIENT_ABSENT: IconUserOff, REFUSED: IconCircleX, WRONG_ADDRESS: IconMapPinOff,
  DAMAGED: IconAlertTriangle, MISSING: IconPackageOff, OTHER: IconDots,
  // Failure contexts
  FAILURE: IconCircleX, ITEM_REFUSED: IconCircleX, ITEM_DAMAGED: IconAlertTriangle, ITEM_MISSING: IconPackageOff,
  // SLA health
  ON_TRACK: IconCircleCheck, MET: IconCircleCheck, AT_RISK: IconAlertTriangle,
  LATE: IconClock, BREACHED: IconAlertOctagon,
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
  const iconPx = size === 'sm' ? 13 : 14;
  const fontSize = size === 'sm' ? 11 : 11;
  const height = size === 'sm' ? 18 : 20;
  const px = size === 'sm' ? 7 : 8;
  const gap = 5;
  const Icon = ICON[status];

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
          display: 'inline-flex',
          alignItems: 'center',
          flexShrink: 0,
          ...(shouldPulse ? { animation: 'asm-pulse 2s ease-in-out infinite' } : {}),
        }}
      >
        {Icon
          ? <Icon size={iconPx} color={cfg.dot} stroke={1.9} />
          : <span style={{ width: dotPx, height: dotPx, borderRadius: '50%', background: cfg.dot, display: 'block' }} />}
      </span>
      <span
        style={{
          fontFamily: "'Clear Sans', system-ui, sans-serif",
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

