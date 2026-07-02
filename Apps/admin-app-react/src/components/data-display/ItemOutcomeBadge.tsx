import { StatusBadge } from '@/components/data-display/StatusBadge';
import { useT } from '@/lib/LocaleContext';

/**
 * Per-line delivery outcome, rendered identically on the route page and the delivery-details page:
 * a {@link StatusBadge} (the SAME colours as the Motifs d'échec / failure-reasons page — REFUSED red,
 * DAMAGED purple, …) followed by the localized motif. Single source of truth so the two pages can't
 * drift. Returns null when there is no outcome, so callers can drop it inline.
 */
export function ItemOutcomeBadge({ outcome, reason, reasonLabel }: { outcome?: string | null; reason?: string | null; reasonLabel?: string | null }) {
  const t = useT();
  if (!outcome) return null;
  // Prefer the server-snapshotted catalog label; fall back to the static dict, then the raw code
  // (covers legacy items / offline codes that were saved before the snapshot existed).
  const display = reasonLabel || (reason ? ((t.itemReasons as Record<string, string>)[reason] ?? reason) : null);
  // Outcome label comes from t.outcomes (REFUSED/DAMAGED/MISSING/…); StatusBadge's own statusLabels
  // fallback doesn't cover item outcomes, so pass it explicitly to avoid raw enum text.
  const outcomeLabel = (t.outcomes as Record<string, string>)[outcome] ?? outcome;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
      <StatusBadge status={outcome} label={outcomeLabel} size="sm" />
      {display && <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>! {display}</span>}
    </span>
  );
}
