package com.asm.delivery.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminStatsResponse {
    private TodayStats today;
    private List<DriverStats> byDriver;
    private List<FailureStats> byFailureCode;

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
}
