package com.asm.delivery.dto.request;

import com.asm.delivery.entity.FailureCode;
import lombok.Data;

@Data
public class FailDeliveryRequest {

    /**
     * Configurable failure-reason code (preferred). Resolved server-side to its
     * analytics category + label. When blank, {@link #failureCode} is used.
     */
    private String failureReasonCode;

    /** Legacy/fallback: raw analytics category enum. */
    private FailureCode failureCode;

    private String failureComment;
}
