import { IconCircleCheck, IconCircleX, IconClock } from '@tabler/icons-react';
import { useT } from '@/lib/i18n/LocaleContext';
import { DRIVER_STATUS_COLORS } from './constants';

interface Props {
  status: string;
  size?: 'sm' | 'md';
}

const ICON: Record<string, typeof IconCircleCheck> = {
  ACTIVE: IconCircleCheck,
  SUSPENDED: IconCircleX,
  PENDING_SETUP: IconClock,
};

/** Account-status pill (ACTIVE / SUSPENDED / PENDING_SETUP), aligned with the design-system palette. */
export function DriverStatusBadge({ status, size = 'md' }: Props) {
  const t = useT();
  const cfg = DRIVER_STATUS_COLORS[status] ?? { dot: 'var(--text-muted)', bg: 'var(--hover-bg)', text: 'var(--text-muted)' };
  const Icon = ICON[status];

  const displayLabel = status === 'ACTIVE' ? t.driversPage.statusActive
    : status === 'SUSPENDED' ? t.driversPage.statusSuspended
    : status === 'PENDING_SETUP' ? t.driversPage.statusPending
    : status;

  const iconPx = size === 'sm' ? 12 : 13;
  const height = size === 'sm' ? 18 : 20;
  const px = size === 'sm' ? 6 : 7;

  return (
    <span
      role="status"
      aria-label={displayLabel}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 5,
        height,
        padding: `0 ${px}px`,
        borderRadius: 99,
        background: cfg.bg,
        flexShrink: 0,
      }}
    >
      {Icon && <Icon size={iconPx} color={cfg.dot} stroke={1.9} />}
      <span
        style={{
          fontFamily: "'Clear Sans', system-ui, sans-serif",
          fontSize: size === 'sm' ? 11 : 11,
          fontWeight: 500,
          color: cfg.text,
          letterSpacing: '-0.01em',
          lineHeight: 1,
          whiteSpace: 'nowrap',
        }}
      >
        {displayLabel}
      </span>
    </span>
  );
}
