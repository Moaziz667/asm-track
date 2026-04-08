package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;
import java.util.Map;
import java.util.List;

@Data
@Builder
public class DashboardKpiResponse {
    // 1. Driver/Delivery Performance
    private double avgDelayMinutes;    // Retard moyen
    private double slaComplianceRate; // % de livraisons à temps
    private long totalCompleted;      // Total livraisons terminées
    
    // 2. Volume & Trends
    private long totalOrdersToday;    // Commandes du jour
    private Map<String, Long> ordersByZone; // Les zones les plus vives (Ville/Quartier)
    private List<DailyVolume> weeklyTrend;  // Commandes par jour (7 derniers jours)
    
    // 3. Exceptions tracking (from Audit Logs)
    private long totalReassigned;     // Nombre de réassignations
    private long totalReplanned;      // Nombre de replanifications

    @Data
    @Builder
    public static class DailyVolume {
        private String date;
        private long count;
    }
}
