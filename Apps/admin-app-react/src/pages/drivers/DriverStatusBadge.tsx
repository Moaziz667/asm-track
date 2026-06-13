import { useT } from '@/lib/LocaleContext';
import { DRIVER_STATUS_COLORS } from './constants';

interface Props {
  status: string;
  size?: 'sm' | 'md';
}

/** Account-status pill (ACTIVE / SUSPENDED / PENDING_SETUP), aligned with the design-system palette. */
export function DriverStatusBadge({ status, size = 'md' }: Props) {
  const t = useT();
  const cfg = DRIVER_STATUS_COLORS[status] ?? { dot: '#8A8F98', bg: 'rgba(138,143,152,0.08)', text: '#6B7280', ribbon: '#8A8F98' };

  const displayLabel = status === 'ACTIVE' ? t.driversPage.statusActive
    : status === 'SUSPENDED' ? t.driversPage.statusSuspended
    : status === 'PENDING_SETUP' ? t.driversPage.statusPending
    : status;

  const dotPx = size === 'sm' ? 5 : 5.5;
  const fontSize = size === 'sm' ? 10 : 11;
  const height = size === 'sm' ? 18 : 20;
  const px = size === 'sm' ? 7 : 8;

  return (
    <span
      role="status"
      aria-label={displayLabel}
      style={{ display: 'inline-flex', alignItems: 'center', gap: 5, height, padding: `0 ${px}px`, borderRadius: 99, background: cfg.bg, flexShrink: 0 }}
    >
      <span style={{ width: dotPx, height: dotPx, borderRadius: '50%', background: cfg.dot, flexShrink: 0 }} />
      <span style={{ fontSize, fontWeight: 500, color: cfg.text, letterSpacing: '-0.01em', lineHeight: 1, whiteSpace: 'nowrap' }}>
        {displayLabel}
      </span>
    </span>
  );
}
