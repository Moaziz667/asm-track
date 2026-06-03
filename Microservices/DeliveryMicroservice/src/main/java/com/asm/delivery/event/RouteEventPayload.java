package com.asm.delivery.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteEventPayload {
    private String routeId;
    private String routeName;
    private String status;
    
    private String driverId;
    private String driverName;
    
    private LocalDate date;
    private LocalTime plannedStartTime;
    private LocalTime plannedEndTime;
    
    private Integer stopCount;
    private String clientName;
    private String erpOrderId;
    private String reason;

    // Pickup-confirmed / route-started precise info
    private String depotName;
    private Integer parcelCount;
    private java.time.LocalDateTime occurredAt;
}
