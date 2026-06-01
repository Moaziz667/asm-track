import { memo } from 'react';
import {
  DRIVER_ACCOUNT_STATUS_COLOR,
  type DriverAccountStatusKey,
} from '@/lib/design-tokens';
import type { DriverAccountStatus } from '@/types';

interface StatusPillProps {
  status?: DriverAccountStatus | null;
  size?: 'sm' | 'md';
  showDot?: boolean;
  label?: string;
  className?: string;
}

function StatusPillImpl({
  status,
  size = 'md',
  showDot = true,
  label,
  className,
}: StatusPillProps) {
  const key: DriverAccountStatusKey = (status ?? 'PENDING_SETUP') as DriverAccountStatusKey;
  const tone = DRIVER_ACCOUNT_STATUS_COLOR[key] ?? DRIVER_ACCOUNT_STATUS_COLOR.PENDING_SETUP;

  const isSm = size === 'sm';
  const sizing = isSm
    ? { height: 20, padding: '0 8px', fontSize: 10, dot: 6 }
    : { height: 24, padding: '0 10px', fontSize: 11, dot: 7 };

  return (
    <span
      className={className}
      role="status"
      aria-label={label ?? tone.label}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        height: sizing.height,
        padding: sizing.padding,
        borderRadius: 999,
        background: tone.bg,
        color: tone.text,
        border: `1px solid ${tone.border}`,
        fontSize: sizing.fontSize,
        fontWeight: 700,
        letterSpacing: '0.02em',
        lineHeight: 1,
        whiteSpace: 'nowrap',
      }}
    >
      {showDot && (
        <span
          aria-hidden
          style={{
            width: sizing.dot,
            height: sizing.dot,
            borderRadius: 999,
            background: tone.dot,
            flexShrink: 0,
          }}
        />
      )}
      {label ?? tone.label}
    </span>
  );
}

export const StatusPill = memo(StatusPillImpl);
export default StatusPill;
