package com.asm.delivery.dto.request;

import lombok.Data;

import java.time.LocalTime;

@Data
public class PatchRouteStopRequest {
    private LocalTime startTimeWindow;
    private LocalTime endTimeWindow;
    private Integer bufferMinutes;
}
