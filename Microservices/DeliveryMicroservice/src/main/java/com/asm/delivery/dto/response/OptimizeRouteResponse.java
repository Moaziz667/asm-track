package com.asm.delivery.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OptimizeRouteResponse {
    private List<RouteStopEtaResponse> optimizedStops;
    private double totalDurationSeconds;
    private double totalDistanceMeters;
    private String routeGeometry;
    private SavingsInfo savings;

    @Data
    @Builder
    public static class SavingsInfo {
        private double durationSavedSeconds;
        private double distanceSavedMeters;
    }
}
