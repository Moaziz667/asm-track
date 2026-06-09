package com.asm.delivery.dto.response;

import com.asm.delivery.entity.DeliveryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOpsOverviewResponse {
    private LocalDateTime generatedAt;
    private String period;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private SlaSnapshot sla;
    private List<LaneSnapshot> lanes;
    private List<ExceptionRow> exceptions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlaSnapshot {
        private int waitingThresholdMinutes;
        private int assignThresholdMinutes;
        private int pickupThresholdMinutes;

        private long waitingBreaches;
        private long assignBreaches;
        private long pickupBreaches;
        private long totalBreaches;

        // Unified SLA (source of truth): live counts from SlaState across all phases.
        private long slaAtRisk;
        private long slaBreached;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaneSnapshot {
        private DeliveryStatus status;
        private String label;
        private long count;
        private List<LaneDelivery> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LaneDelivery {
        private UUID deliveryId;
        private UUID orderId;
        private String orderRef;
        private String clientName;
        private String city;
        private String driverName;
        private LocalDateTime createdAt;
        private LocalDateTime scheduledAt;
        private UUID routeId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExceptionRow {
        private UUID deliveryId;
        private UUID orderId;
        private String orderRef;
        private DeliveryStatus status;
        private String clientName;
        private String city;
        private String driverName;
        private String severity;
        private String message;
        private LocalDateTime createdAt;
        private LocalDateTime scheduledAt;
        private UUID routeId;
        private String routeName;
        private String routeStatus;
        private String slaPhase;
        private String slaHealth;
    }
}