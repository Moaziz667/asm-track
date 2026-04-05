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

    /** Optional depot ID — every route should start from a depot. */
    private UUID depotId;

    /** Optional explicit departure time; defaults to date + plannedStartTime. */
    private java.time.LocalDateTime departureTime;
}
