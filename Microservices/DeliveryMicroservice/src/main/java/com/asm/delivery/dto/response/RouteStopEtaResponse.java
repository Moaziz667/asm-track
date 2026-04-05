package com.asm.delivery.dto.response;

import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.SlaStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class RouteStopEtaResponse {
    private UUID stopId;
    private Integer sequenceOrder;
    private String deliveryAddress;
    private LocalDateTime etaAt;
    private LocalDateTime slaDeadline;
    private SlaStatus slaStatus;
    private Integer driveDurationSeconds;
    private Integer driveDistanceMeters;
    private LocalDateTime actualArrivalAt;
    private RouteStopStatus status;
    private Integer dwellMinutes;
}
