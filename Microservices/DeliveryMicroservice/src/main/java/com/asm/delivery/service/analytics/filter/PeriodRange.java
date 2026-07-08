package com.asm.delivery.service.analytics.filter;

import java.time.LocalDateTime;

/**
 * Resolved time window for an analytics query — the single output of {@link PeriodResolver}.
 *
 * <p>All bounds are {@code LocalDateTime} in <b>Africa/Tunis</b> wall-clock, matching the
 * {@code timestamp without time zone} columns (the whole stack is pinned to Tunis). {@code start}
 * is inclusive, {@code end} exclusive-ish (we query {@code createdAt <= end} downstream, end is the
 * instant "now" or end-of-day).
 *
 * <p>{@code prevStart}/{@code prevEnd} are non-null only when comparison is requested; they describe
 * the immediately-preceding window of equal length (for period-over-period deltas).
 *
 * @param label       human/debug label of the resolved range (e.g. "today", "last7d", "custom")
 * @param start       inclusive start (Tunis wall-clock)
 * @param end         end bound (Tunis wall-clock)
 * @param granularity bucket size for time-series: "hour" | "day" | "week"
 * @param prevStart   previous-window start, or null when compare is off
 * @param prevEnd     previous-window end, or null when compare is off
 */
public record PeriodRange(
        String label,
        LocalDateTime start,
        LocalDateTime end,
        String granularity,
        LocalDateTime prevStart,
        LocalDateTime prevEnd
) {
    /** True when a previous window was derived (comparison requested and resolvable). */
    public boolean hasComparison() {
        return prevStart != null && prevEnd != null;
    }
}
