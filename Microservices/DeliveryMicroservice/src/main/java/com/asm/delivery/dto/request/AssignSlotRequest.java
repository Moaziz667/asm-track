package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AssignSlotRequest {

    @NotNull
    private UUID deliveryId;

    @NotNull
    private UUID slotId;

    private String note;

    private Boolean allowOverride;

    private String overrideReason;
}
