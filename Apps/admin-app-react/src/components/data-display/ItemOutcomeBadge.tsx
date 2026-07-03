import { StatusBadge } from '@/components/data-display/StatusBadge';
import { Tooltip, TooltipTrigger, TooltipContent } from '@/components/ui/tooltip';
import { IconInfoCircle } from '@tabler/icons-react';
import { useT } from '@/lib/LocaleContext';

/**
 * Per-line delivery outcome, rendered identically on the route page and the delivery-details page:
 * a {@link StatusBadge} (the SAME colours as the Motifs d'échec / failure-reasons page — REFUSED red,
 * DAMAGED purple, …) with the localized motif. Single source of truth so the two pages can't drift.
 * Returns null when there is no outcome, so callers can drop it inline.
 *
 * `reasonInTooltip` moves the motif from an inline caption into a hover/focus tooltip, with a small ⓘ
 * affordance next to the badge — keeps a dense status column scannable (Carbon / Shopify guidance).
 * Requires a {@code <TooltipProvider>} ancestor.
 */
export function ItemOutcomeBadge({ outcome, reason, reasonLabel, reasonInTooltip = false }: { outcome?: string | null; reason?: string | null; reasonLabel?: string | null; reasonInTooltip?: boolean }) {
  const t = useT();
  if (!outcome) return null;
  // Prefer the server-snapshotted catalog label; fall back to the static dict, then the raw code
  // (covers legacy items / offline codes that were saved before the snapshot existed).
  const display = reasonLabel || (reason ? ((t.itemReasons as Record<string, string>)[reason] ?? reason) : null);
  // Outcome label comes from t.outcomes (REFUSED/DAMAGED/MISSING/…); StatusBadge's own statusLabels
  // fallback doesn't cover item outcomes, so pass it explicitly to avoid raw enum text.
  const outcomeLabel = (t.outcomes as Record<string, string>)[outcome] ?? outcome;
  const badge = <StatusBadge status={outcome} label={outcomeLabel} size="sm" />;

  if (reasonInTooltip) {
    if (!display) return badge;
    return (
      <Tooltip>
        <TooltipTrigger render={
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, cursor: 'help', width: 'fit-content' }} aria-label={display} />
        }>
          {badge}
          <IconInfoCircle size={13} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
        </TooltipTrigger>
        <TooltipContent>{display}</TooltipContent>
      </Tooltip>
    );
  }

  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
      {badge}
      {display && <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>! {display}</span>}
    </span>
  );
}
