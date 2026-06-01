/**
 * Shim — delegates to the canonical StatusBadge in data-display.
 * Kept for backward-compat imports; do not add new usages here.
 */
export { StatusBadge as default } from '@/components/data-display/StatusBadge';

/** Colour map kept for deliveries/page.tsx ribbon indicator. Aligned with StatusBadge Linear palette. */
export const STATUS_COLORS: Record<string, string> = {
  UNSCHEDULED:         '#C4881A',
  SCHEDULED:           '#5E6AD2',
  PICKED_UP:           '#2594B8',
  IN_TRANSIT:          '#D4772C',
  DELIVERED:           '#4CAF82',
  PARTIALLY_DELIVERED: '#7B6FCC',
  PARTIAL:             '#7B6FCC',
  CANCELLED:           '#8A8F98',
  FAILED:              '#C7372F',
  FAILED_ATTEMPT:      '#C7372F',
  SLA_BREACH:          '#C7372F',
  DRAFT:               '#8A8F98',
  VALIDATED:           '#5E6AD2',
  IN_PROGRESS:         '#5E6AD2',
  CLOSED:              '#4CAF82',
  COMPLETED:           '#4CAF82',
  REMOVED_REPLANNED:   '#C4881A',
  REMOVED_CANCELLED:   '#8A8F98',
};

