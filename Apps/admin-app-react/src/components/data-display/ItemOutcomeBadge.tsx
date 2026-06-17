import { StatusBadge } from '@/components/data-display/StatusBadge';
import { useT } from '@/lib/LocaleContext';

/**
 * Per-line delivery outcome, rendered identically on the route page and the delivery-details page:
 * a {@link StatusBadge} (the SAME colours as the Motifs d'échec / failure-reasons page — REFUSED red,
 * DAMAGED purple, …) followed by the localized motif. Single source of truth so the two pages can't
 * drift. Returns null when there is no outcome, so callers can drop it inline.
 */
export function ItemOutcomeBadge({ outcome, reason }: { outcome?: string | null; reason?: string | null }) {
  const t = useT();
  if (!outcome) return null;
  const reasonLabel = reason ? ((t.itemReasons as Record<string, string>)[reason] ?? reason) : null;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
      <StatusBadge status={outcome} size="sm" />
      {reasonLabel && <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>{reasonLabel}</span>}
    </span>
  );
}
