package com.asm.delivery.dto.request;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

@Data
public class UpdateRouteRequest {
    private String name;
    private UUID driverId;
    private UUID vehicleId;
    private LocalDate date;
    private LocalTime plannedStartTime;
    private LocalTime plannedEndTime;
    private String city;
    private UUID depotId;
    private LocalDateTime departureTime;
}
