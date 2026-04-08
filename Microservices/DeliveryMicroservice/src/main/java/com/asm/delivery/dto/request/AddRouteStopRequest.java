package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AddRouteStopRequest {
    @NotNull
    private UUID deliveryId;

    private java.time.LocalTime startTimeWindow;
    private java.time.LocalTime endTimeWindow;
    private Integer bufferMinutes;
}
