package com.asm.delivery.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
@Schema(description = "Partial route update payload")
public class UpdateRouteRequest {
    @Schema(description = "Route display name")
    private String name;

    @Schema(description = "Assigned driver id")
    private UUID driverId;

    @Schema(description = "Assigned vehicle id")
    private UUID vehicleId;

    @Schema(description = "Planned route date", example = "2026-04-08")
    private LocalDate date;

    @Schema(description = "Start time in HH:mm[:ss]", example = "08:30")
    private String plannedStartTime;

    @Schema(description = "End time in HH:mm[:ss]", example = "13:30")
    private String plannedEndTime;

    @Schema(description = "Operational city")
    private String city;

    @Schema(description = "Departure depot id")
    private UUID depotId;

    @Schema(description = "Explicit departure datetime", example = "2026-04-08T08:30:00")
    private LocalDateTime departureTime;
    
    // Add these to support updating stops and their configurations
    @Schema(description = "Ordered delivery ids to replace current stops")
    private List<UUID> deliveryIds;

    @Schema(description = "Optional per-stop scheduling config")
    private List<StopConfig> stopConfigs;

    @Data
    @Schema(description = "Per-stop update config")
    public static class StopConfig {
        @Schema(description = "Stop delivery id")
        private UUID deliveryId;

        @Schema(description = "Manual start window in HH:mm[:ss]", example = "09:00")
        private String startTimeWindow;

        @Schema(description = "Manual end window in HH:mm[:ss]", example = "10:00")
        private String endTimeWindow;

        @Schema(description = "SLA buffer in minutes", example = "15")
        private int bufferMinutes;
    }
}
