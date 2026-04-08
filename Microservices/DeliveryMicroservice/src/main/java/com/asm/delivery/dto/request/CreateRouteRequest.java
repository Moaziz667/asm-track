package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
public class CreateRouteRequest {
    @NotBlank
    private String name;

    @NotNull
    private UUID driverId;

    private UUID vehicleId;

    @NotNull
    private LocalDate date;

    private LocalTime plannedStartTime;

    private LocalTime plannedEndTime;

    private String city;

    private List<UUID> deliveryIds;

    private List<StopConfig> stopConfigs;

    @Data
    public static class StopConfig {
        private UUID deliveryId;
        private java.time.LocalTime startTimeWindow;
        private java.time.LocalTime endTimeWindow;
        private int bufferMinutes;
    }

    /** Depot where the route departs from — required for ETA/optimization. */
    @NotNull(message = "Depot is required for ETA calculation")
    private UUID depotId;

    /** Optional explicit departure time; defaults to date + plannedStartTime. */
    private java.time.LocalDateTime departureTime;
}
