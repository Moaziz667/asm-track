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

    @Data
    @Builder
    @Schema(description = "Daily volume point")
    public static class DailyVolume {
        @Schema(description = "ISO date", example = "2026-04-08")
        private String date;

        @Schema(description = "Order count for date")
        private long count;
    }
}
