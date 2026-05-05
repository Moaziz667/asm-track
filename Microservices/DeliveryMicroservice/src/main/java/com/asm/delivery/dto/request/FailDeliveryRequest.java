package com.asm.delivery.dto.request;

import com.asm.delivery.entity.FailureCode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FailDeliveryRequest {
    @NotNull
    private FailureCode failureCode;

    private String failureComment;
}
