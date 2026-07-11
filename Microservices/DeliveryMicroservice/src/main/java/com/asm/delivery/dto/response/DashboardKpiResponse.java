package com.asm.delivery.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import java.util.Map;
import java.util.List;

@Data
@Builder
@Schema(description = "Dashboard KPI payload used by admin analytics UI")
public class DashboardKpiResponse {
    @Schema(description = "Average delay in minutes across measurable completed deliveries")
    private double avgDelayMinutes;    // Retard moyen
    
    // 2. Volume & Trends
    @Schema(description = "Total orders created today")
    private long totalOrdersToday;    // Commandes du jour
    @Schema(description = "Order volumes grouped by zone/city")
    private Map<String, Long> ordersByZone; // Les zones les plus vives (Ville/Quartier)
    @Schema(description = "Daily volume trend on last 7 days")
    private List<DailyVolume> weeklyTrend;  // Commandes par jour (7 derniers jours)
    
    // 3. Exceptions tracking (from Audit Logs)
    @Schema(description = "Count of reassignment actions")
    private long totalReassigned;     // Nombre de réassignations
    @Schema(description = "Count of replan actions")
    private long totalReplanned;      // Nombre de replanifications

    // 4. Period-over-period deltas (current range vs the immediately preceding range of equal length)
    @Schema(description = "Total orders in the immediately preceding period of equal length")
    private long previousPeriodOrders;
    @Schema(description = "SLA compliance (%) for the current period")
    private double slaRate;
    @Schema(description = "SLA compliance (%) for the preceding period")
    private double previousSlaRate;
    @Schema(description = "Late deliveries in the period — measurable completed deliveries that missed their SLA window")
    private long lateOrders;
    @Schema(description = "Measurable completed deliveries in the period (the denominator for the late rate)")
    private long measurableOrders;

    // Period-over-period counterparts for the top KPI deltas (all vs the preceding window of equal length).
    @Schema(description = "Delivered (incl. partial) in the current period")
    private long deliveredOrders;
    @Schema(description = "Delivered (incl. partial) in the preceding period")
    private long previousDelivered;
    @Schema(description = "Failure events in the current period (failedAt-anchored)")
    private long failedOrders;
    @Schema(description = "Failure events in the preceding period")
    private long previousFailed;
    @Schema(description = "Late deliveries in the preceding period")
    private long previousLate;

    @Data
    @Builder
    @Schema(description = "Daily volume point")
    public static class DailyVolume {
        @Schema(description = "ISO date", example = "2026-04-08")
        private String date;

        @Schema(description = "Order count for date")
        private long count;

        @Schema(description = "Delivered (incl. partial) count for date")
        private long delivered;

        @Schema(description = "Failed count for date")
        private long failed;
    }
}
