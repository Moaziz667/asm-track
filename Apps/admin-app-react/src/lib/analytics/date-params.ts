/**
 * Build the date portion of an analytics query.
 *
 * The backend `PeriodResolver` expresses a custom window ONLY through explicit `from`/`to`; the string
 * `range=custom` is NOT a recognized preset and returns 400. The UI, however, flips `range` to `custom`
 * the moment the user picks the "Custom" preset — before both dates are entered. Emitting a bare
 * `range=custom` in that transient state is what surfaced the "filter → error" toasts across the dashboard,
 * analyse, and performance pages.
 *
 * Rule: custom + both dates → send `from`/`to` (no `range`); custom without both dates → fall back to a
 * valid preset so the request never 400s; any other preset → send `range`.
 */
export function analyticsDateParams(
  range: string,
  from?: string,
  to?: string,
  fallback = 'last30d',
): Record<string, string> {
  if (range === 'custom') {
    return from && to
      ? { from: `${from}T00:00:00`, to: `${to}T23:59:59` }
      : { range: fallback };
  }
  return { range };
}
