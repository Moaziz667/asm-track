import { IconCircleCheck, IconCircleX, IconClock } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';
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
      className="inline-flex items-center shrink-0 rounded-full border"
      style={{
        gap: 4,
        height,
        padding: `0 ${px}px`,
        background: cfg.bg,
        color: cfg.text,
        borderColor: `color-mix(in srgb, ${cfg.dot} 18%, transparent)`,
      }}
    >
      {Icon && <Icon size={iconPx} color={cfg.dot} stroke={2} />}
      <span className="text-2xs font-medium whitespace-nowrap">
        {displayLabel}
      </span>
    </span>
  );
}
