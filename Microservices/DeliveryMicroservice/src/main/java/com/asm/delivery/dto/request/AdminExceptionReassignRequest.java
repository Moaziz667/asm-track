package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;
import java.util.UUID;

@Data
public class AdminExceptionReassignRequest {
    @NotNull
    private UUID driverId;

    @Size(max = 500)
    private String note;

    private LocalTime startTimeWindow;
    private LocalTime endTimeWindow;

    private UUID targetRouteId;
    private Integer insertAtOrder;

    /** Dispatcher explicitly accepts overloading the target vehicle (soft-block override). */
    private boolean acknowledgeOverload;
}
