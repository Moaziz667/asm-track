package com.asm.delivery.service.analytics.filter;

import com.asm.delivery.exception.AppException;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for resolving an analytics time window.
 *
 * <p>Anchors everything to <b>Africa/Tunis</b> explicitly (business "today"), so results are correct
 * regardless of the container's default timezone. Produces {@link PeriodRange} bounds as
 * {@code LocalDateTime} in Tunis wall-clock — matching the {@code timestamp without time zone}
 * columns the stats queries filter on.
 *
 * <p>Accepts, in priority order:
 * <ol>
 *   <li><b>from/to</b> — explicit custom window (each may be a date at start/end of day).</li>
 *   <li><b>last</b> — relative window like {@code "6h"}, {@code "90d"} (last N hours/days up to now).</li>
 *   <li><b>range</b> — a preset: today, yesterday, wtd, last7d, mtd, last30d, qtd, ytd, all.
 *       Legacy aliases (day/week/month/custom) are honored for backward compatibility.</li>
 * </ol>
 *
 * <p>Granularity is auto-derived from the span (hour ≤ 48h, day ≤ 90d, else week) unless an explicit
 * one is supplied. When {@code compare} is true, the immediately-preceding window of equal length is
 * derived for period-over-period deltas.
 */
@Component
public class PeriodResolver {

    public static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");

    private static final LocalDateTime EPOCH_START = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final Pattern LAST_PATTERN = Pattern.compile("^(\\d+)\\s*([hdwm])$", Pattern.CASE_INSENSITIVE);

    /** Business "now" in Tunis wall-clock. */
    public LocalDateTime now() {
        return LocalDateTime.now(TUNIS);
    }

    /**
     * Resolve a window from the unified param set. Any argument may be null.
     *
     * @param range       preset key (today, yesterday, wtd, last7d, mtd, last30d, qtd, ytd, all, or
     *                    legacy day/week/month/custom). Defaults to "today" when null/blank.
     * @param last        relative window like "6h"/"90d" — overrides {@code range} when present.
     * @param from        explicit start — overrides {@code range}/{@code last} when both from&to set.
     * @param to          explicit end.
     * @param granularity "hour"|"day"|"week", or null to auto-derive from the span.
     * @param compare     when true, derive the preceding window of equal length.
     */
    public PeriodRange resolve(String range, String last, LocalDateTime from, LocalDateTime to,
                               String granularity, boolean compare) {
        LocalDateTime now = now();
        String label;
        LocalDateTime start;
        LocalDateTime end;

        if (from != null && to != null) {
            if (to.isBefore(from)) {
                throw AppException.badRequest("'to' must be greater than or equal to 'from'");
            }
            label = "custom";
            start = from;
            end = to;
        } else if (from != null || to != null) {
            throw AppException.badRequest("Custom window requires both 'from' and 'to'");
        } else if (last != null && !last.isBlank()) {
            start = resolveLast(last, now);
            end = now;
            label = "last:" + last.trim().toLowerCase(Locale.ROOT);
        } else {
            String key = (range == null || range.isBlank()) ? "today" : range.trim().toLowerCase(Locale.ROOT);
            LocalDate today = now.toLocalDate();
            switch (key) {
                case "today", "day" -> { start = today.atStartOfDay(); end = now; }
                case "yesterday" -> {
                    start = today.minusDays(1).atStartOfDay();
                    end = today.atStartOfDay();
                }
                case "wtd", "week" -> { start = today.with(DayOfWeek.MONDAY).atStartOfDay(); end = now; }
                case "last7d" -> { start = today.minusDays(6).atStartOfDay(); end = now; }
                case "mtd", "month" -> { start = today.withDayOfMonth(1).atStartOfDay(); end = now; }
                case "last30d" -> { start = today.minusDays(29).atStartOfDay(); end = now; }
                case "qtd" -> {
                    LocalDate firstOfQuarter = today.with(IsoFields.DAY_OF_QUARTER, 1L);
                    start = firstOfQuarter.atStartOfDay();
                    end = now;
                }
                case "ytd", "year" -> { start = today.withDayOfYear(1).atStartOfDay(); end = now; }
                case "all" -> { start = EPOCH_START; end = now; }
                default -> throw AppException.badRequest("Unknown range: " + key);
            }
            label = key;
        }

        String gran = resolveGranularity(granularity, start, end);
        LocalDateTime prevStart = null;
        LocalDateTime prevEnd = null;
        if (compare && !"all".equals(label)) {
            Duration span = Duration.between(start, end);
            prevEnd = start;
            prevStart = start.minus(span);
        }
        return new PeriodRange(label, start, end, gran, prevStart, prevEnd);
    }

    /** Convenience for legacy callers: {@code period} + date-only {@code from}/{@code to}. */
    public PeriodRange resolveLegacy(String period, LocalDate from, LocalDate to) {
        boolean custom = "custom".equalsIgnoreCase(period == null ? "" : period.trim());
        if (custom || (from != null && to != null)) {
            if (from == null || to == null) {
                throw AppException.badRequest("For custom period, both 'from' and 'to' are required");
            }
            return resolve(null, null, from.atStartOfDay(), to.atTime(LocalTime.MAX), null, false);
        }
        return resolve(period, null, null, null, null, false);
    }

    private LocalDateTime resolveLast(String last, LocalDateTime now) {
        Matcher m = LAST_PATTERN.matcher(last.trim());
        if (!m.matches()) {
            throw AppException.badRequest("Invalid 'last' value: " + last + " (expected e.g. 6h, 90d)");
        }
        long n = Long.parseLong(m.group(1));
        if (n <= 0) {
            throw AppException.badRequest("'last' must be positive: " + last);
        }
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "h" -> now.minusHours(n);
            case "d" -> now.minusDays(n);
            case "w" -> now.minusWeeks(n);
            case "m" -> now.minusMonths(n);
            default -> throw AppException.badRequest("Invalid 'last' unit: " + last);
        };
    }

    /**
     * Auto bucket size when not supplied: hour for ≤ 48h spans, day for ≤ 90d, week beyond.
     * An explicit "hour" on a span &gt; 7 days is rejected to prevent unbounded point dumps.
     */
    private String resolveGranularity(String requested, LocalDateTime start, LocalDateTime end) {
        Duration span = Duration.between(start, end);
        if (requested != null && !requested.isBlank()) {
            String g = requested.trim().toLowerCase(Locale.ROOT);
            if (!g.equals("hour") && !g.equals("day") && !g.equals("week")) {
                throw AppException.badRequest("Unknown granularity: " + g);
            }
            if (g.equals("hour") && span.toDays() > 7) {
                throw AppException.badRequest("Hourly granularity is limited to windows of 7 days or less");
            }
            return g;
        }
        if (span.toHours() <= 48) return "hour";
        if (span.toDays() <= 90) return "day";
        return "week";
    }
}
