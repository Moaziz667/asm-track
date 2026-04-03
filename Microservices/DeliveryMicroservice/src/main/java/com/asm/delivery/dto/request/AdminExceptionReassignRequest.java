package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class AdminExceptionReassignRequest {
    @NotNull
    private UUID driverId;

    @Size(max = 500)
    private String note;
}
