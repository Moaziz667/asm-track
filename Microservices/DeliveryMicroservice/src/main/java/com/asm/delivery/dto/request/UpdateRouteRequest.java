package com.asm.delivery.dto.request;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
public class UpdateRouteRequest {
    private String name;
    private UUID driverId;
    private UUID vehicleId;
    private LocalDate date;
    private String plannedStartTime;
    private String plannedEndTime;
    private String city;
    private UUID depotId;
    private LocalDateTime departureTime;
    
    // Add these to support updating stops and their configurations
    private List<UUID> deliveryIds;
    private List<StopConfig> stopConfigs;

    @Data
    public static class StopConfig {
        private UUID deliveryId;
        private String startTimeWindow;
        private String endTimeWindow;
        private int bufferMinutes;
    }
}
