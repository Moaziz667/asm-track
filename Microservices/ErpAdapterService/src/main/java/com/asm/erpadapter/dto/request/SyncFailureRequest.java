package com.asm.erpadapter.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Request to post a failure note on an ERP order. */
@Data
public class SyncFailureRequest {
    @NotBlank private String erpOrderId;
    private String failureCode;
    private String comment;
}
