import { useT } from '@/lib/LocaleContext';
import { cn } from '@/lib/utils';

/**
 * Standard failure presentation shared across delivery / dispatch / route / timeline.
 * Shows the canonical category (failureCode → localized label, the thing ERP sync keys
 * off) as a red badge, followed by the specific reason text (failReason — the catalog
 * motif enriched with the driver's comment). Either part is optional.
 */
export function FailureInfo({
  code,
  reason,
  size = 'sm',
  className,
}: {
  code?: string | null;
  reason?: string | null;
  size?: 'sm' | 'xs';
  className?: string;
}) {
  const t = useT();
  if (!code && !reason) return null;

  const label = code ? ((t.failureCodes as any)?.[code] ?? code) : null;
  const badgeText = size === 'xs' ? 'text-[9px]' : 'text-2xs';
  const reasonText = size === 'xs' ? 'text-2xs' : 'text-xs';

  return (
    <span className={cn('inline-flex items-center gap-1.5 flex-wrap', className)}>
      {label && (
        <span className={cn(badgeText, 'font-bold px-2 py-0.5 rounded-[3px] bg-red-50 text-red-700 border border-red-200 whitespace-nowrap')}>
          {label}
        </span>
      )}
      {reason && (
        <span className={cn(reasonText, 'font-medium text-[var(--text-secondary)]')}>
          {reason}
        </span>
      )}
    </span>
  );
}

export default FailureInfo;
