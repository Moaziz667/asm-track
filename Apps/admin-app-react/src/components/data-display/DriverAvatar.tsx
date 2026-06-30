import { useState } from 'react';
import { cn } from '@/lib/utils';
import { useDriverAvatars } from '@/hooks/useDriverAvatars';

/**
 * The one driver avatar used everywhere a driver is shown (drivers table, handoff cards, reassign
 * drawer, dashboard fleet, stats, reports). Renders the photo when present, falling back to mono
 * initials — and also falls back if the image fails to load. Status dots are added by the caller.
 */
export function DriverAvatar({
  name, photoUrl, size = 32, className,
}: {
  name?: string;
  photoUrl?: string | null;
  size?: number;
  className?: string;
}) {
  const [errored, setErrored] = useState(false);
  const initials =
    (name ?? '').split(/\s+/).map(p => p[0]).filter(Boolean).join('').slice(0, 2).toUpperCase() || '—';
  const showImg = !!photoUrl && !errored;

  return (
    <div
      className={cn(
        'relative shrink-0 overflow-hidden rounded-full border border-[var(--border)] flex items-center justify-center select-none',
        className,
      )}
      style={{ width: size, height: size, background: 'var(--surface)' }}
      title={name}
    >
      {showImg ? (
        <img
          src={photoUrl!}
          alt={name ?? ''}
          width={size}
          height={size}
          loading="lazy"
          className="w-full h-full object-cover"
          onError={() => setErrored(true)}
        />
      ) : (
        <span
          className="font-mono font-bold text-[var(--brand)] leading-none"
          style={{ fontSize: Math.max(9, Math.round(size * 0.34)) }}
        >
          {initials}
        </span>
      )}
    </div>
  );
}

/** Avatar for surfaces that only have a driver id — resolves the photo via the shared cached map. */
export function DriverAvatarById({
  driverId, name, size, className,
}: {
  driverId?: string | null;
  name?: string;
  size?: number;
  className?: string;
}) {
  const map = useDriverAvatars();
  return <DriverAvatar name={name} photoUrl={driverId ? map[driverId] : undefined} size={size} className={className} />;
}
