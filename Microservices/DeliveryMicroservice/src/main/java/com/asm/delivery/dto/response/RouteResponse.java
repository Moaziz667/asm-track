package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class RouteResponse {
    private UUID id;
    private String name;
    private UUID driverId;
    private UUID vehicleId;
    private LocalDate date;
    private LocalTime plannedStartTime;
    private LocalTime plannedEndTime;
    private String zone;
    private String city;
    private RouteStatus status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime validatedAt;
    private LocalDateTime closedAt;
    private Integer totalStops;
    private Integer completedStops;
    private Integer failedStops;
    private Integer partialStops;
    private Integer pendingStops;
    private Double progressPercent;
    private Long etaDriftMinutes;
    private List<RouteStopResponse> stops;
}
