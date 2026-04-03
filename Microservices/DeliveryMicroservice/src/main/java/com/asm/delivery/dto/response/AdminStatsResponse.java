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
    private List<CityStats> byCity;
    private List<ClientStats> byClient;

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
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailureStats {
        private String code;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CityStats {
        private String city;
        private long total;
        private long delivered;
        private long failed;
        private double successRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClientStats {
        private String clientName;
        private long total;
        private long delivered;
        private long failed;
        private double successRate;
    }
}
