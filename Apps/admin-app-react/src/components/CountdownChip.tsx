import { memo, useEffect, useState } from 'react';
import { IconClock, IconAlertTriangle } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';

interface CountdownChipProps {
  expiresAt: string | null | undefined;
  className?: string;
}

function diffText(ms: number): string {
  if (ms <= 0) return '0m';
  const totalMinutes = Math.floor(ms / 60_000);
  const days = Math.floor(totalMinutes / (60 * 24));
  const hours = Math.floor((totalMinutes % (60 * 24)) / 60);
  const minutes = totalMinutes % 60;
  if (days > 0) return `${days}j ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

function CountdownChipImpl({ expiresAt, className }: CountdownChipProps) {
  const t = useT();
  const target = expiresAt ? new Date(expiresAt).getTime() : null;

  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!target) return;
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, [target]);

  if (!target) return null;

  const diff = target - now;
  const expired = diff <= 0;

  return (
    <span
      className={className}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 4,
        height: 22,
        padding: '0 8px',
        borderRadius: 999,
        background: expired ? '#FEE2E2' : '#F1F5F9',
        color: expired ? '#991B1B' : '#475569',
        border: `1px solid ${expired ? '#FECACA' : '#E2E8F0'}`,
        fontSize: 10,
        fontWeight: 700,
        letterSpacing: '0.02em',
        lineHeight: 1,
        whiteSpace: 'nowrap',
      }}
    >
      {expired ? <IconAlertTriangle size={11} /> : <IconClock size={11} />}
      {expired
        ? t.driversPage.invitationExpiredChip
        : t.driversPage.invitationExpiresIn.replace('{time}', diffText(diff))}
    </span>
  );
}

export const CountdownChip = memo(CountdownChipImpl);
export default CountdownChip;
