/**
 * What counts as an exception a dispatcher still has to do something about.
 *
 * <p>This existed twice, and the two copies did not agree. The sidebar badge asked the server for
 * today's exceptions and kept the CRITICAL and WARNING ones; the dispatch desk asked for all of
 * them and kept everything. So the badge said 3, the page it led to showed forty rows — and the
 * three it counted were not even the outstanding ones, because an order that failed yesterday and
 * is still waiting on a human fell outside "today" and went uncounted. A badge that both overstates
 * what a click will show and understates the backlog is worse than no badge.
 *
 * <p>One rule, read from one file, and the number on the menu is now a promise the page keeps.
 */

/** The shape both callers share — deliberately loose, they come from different response types. */
export interface AttentionCandidate {
  status?: string;
  severity?: string;
  motif?: string;
}

/**
 * True when the row is work, not commentary.
 *
 * <p>Excluded, and only these:
 * <ul>
 *   <li>{@code CANCELLED} — terminal. Nobody can act on it, and the classifier says as much.</li>
 *   <li>{@code SCHEDULED_MONITORING} — the classifier's own word for "being watched, on time".
 *       It is raised so the desk can display a delivery, not so anyone chases it.</li>
 *   <li>Anything below WARNING — INFO is context.</li>
 * </ul>
 */
export function needsAttention(row: AttentionCandidate): boolean {
  if (row.status === 'CANCELLED') return false;
  if (row.motif === 'SCHEDULED_MONITORING') return false;
  return row.severity === 'CRITICAL' || row.severity === 'WARNING';
}

export const countNeedingAttention = (rows: AttentionCandidate[]): number =>
  rows.reduce((n, r) => n + (needsAttention(r) ? 1 : 0), 0);
