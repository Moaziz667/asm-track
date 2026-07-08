package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Driver-performance scorecard payload for the Analyse page (leaderboard + drilldown).
 *
 * <p>Each {@link Scorecard} carries the "true stats" the fleet-analytics UIs expect: volume, success
 * rate, on-time rate, average delay, top failure motif, and period-over-period deltas. The daily
 * {@code trend} is populated only for a single-driver drilldown (heavy per driver).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverScorecardResponse {

    private String period;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    /** True when deltas were computed against the preceding window. */
    private boolean compared;
    private List<Scorecard> drivers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scorecard {
        private String driverId;
        private String driverName;
        private long volume;
        private long delivered;
        private long failed;
        private double successRate;
        /** On-time deliveries / measurable deliveries (windowed SLA), as a percentage. */
        private double onTimeRate;
        private double avgDelayMinutes;
        private String topFailureMotif;
        /** vs previous window; null when comparison was not requested/derivable. */
        private Double deltaVolumePct;
        private Double deltaSuccessRatePts;
        /** Daily series — only for single-driver drilldown, else null. */
        private List<DailyPoint> trend;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyPoint {
        private String date;
        private long total;
        private long delivered;
    }
}
