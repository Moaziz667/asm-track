package com.asm.delivery.dto.analytics;

import com.asm.delivery.entity.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Unified analytics query contract — the single param vocabulary for every stats endpoint
 * (dashboard, global KPIs, ops overview, driver scorecards). Bound from query parameters via
 * {@code @ModelAttribute}, so all fields are optional and camelCase.
 *
 * <p>Time is resolved by {@code PeriodResolver} (priority: from/to &gt; last &gt; range); scope is
 * resolved into an {@code AnalyticsFilter}. Keeps the legacy {@code period} field for backward
 * compatibility with existing callers.
 */
@Data
public class AnalyticsQuery {

    @Schema(description = "Preset range: today, yesterday, wtd, last7d, mtd, last30d, qtd, ytd, all",
            example = "today")
    private String range;

    @Schema(description = "Legacy preset (day/week/month/all/custom) — kept for backward compatibility")
    private String period;

    @Schema(description = "Relative window, e.g. 6h, 90d (overrides range)", example = "7d")
    private String last;

    @Schema(description = "Explicit start (ISO-8601, interpreted as Africa/Tunis)", example = "2026-07-08T06:00:00")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime from;

    @Schema(description = "Explicit end (ISO-8601, interpreted as Africa/Tunis)", example = "2026-07-08T14:00:00")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime to;

    @Schema(description = "Bucket size for time-series: hour | day | week (auto when omitted)")
    private String granularity;

    @Schema(description = "Also compute the immediately-preceding window for period-over-period deltas")
    private boolean compare;

    // ── Scope filters (server-side, multi-value: repeated params zone=A&zone=B) ────────────
    @Schema(description = "Filter by zone name(s)", example = "Tunis")
    private List<String> zone;

    @Schema(description = "Filter by driver id(s)")
    private List<UUID> driverId;

    @Schema(description = "Filter by delivery status(es)")
    private List<DeliveryStatus> status;

    @Schema(description = "Filter by failure code / motif(s)", example = "CLIENT_ABSENT")
    private List<String> motif;

    @Schema(description = "Filter by drop-off city(ies)", example = "Tunis")
    private List<String> city;

    @Schema(description = "Filter by order source(s)", example = "ODOO")
    private List<com.asm.delivery.entity.OrderSource> source;

    @Schema(description = "Filter by depot(s) the delivery is routed from")
    private List<java.util.UUID> depot;
}
