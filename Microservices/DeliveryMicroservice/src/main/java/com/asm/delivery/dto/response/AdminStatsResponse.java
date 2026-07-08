package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminStatsResponse {
    private TodayStats today;
    private List<DriverStats> byDriver;
    private List<FailureStats> byFailureCode;
    private List<ItemStats> topItems;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemStats {
        private String sku;
        private String name;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TodayStats {
        private long total;
        private long delivered;
        private long failed;
        private long inTransit;
        private long waiting;
        private long assigned;
        private double successRate;
        private double partialRate;
        private long partialCount;
        private double avgAssignToPickupMinutes;
        private double avgPickupToTransitMinutes;
        private double avgTransitToCompletionMinutes;
        private String period;
        private LocalDateTime periodStart;
        private LocalDateTime periodEnd;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DriverStats {
        private String driverId;
        private String driverName;
        private long total;
        private long delivered;
        private long failed;
        private double successRate;
        private double avgDelayMinutes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailureStats {
        private String code;
        private long count;
    }

}
