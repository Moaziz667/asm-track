package com.asm.erpadapter.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Request to sync full delivery to the ERP. */
@Data
public class SyncFullDeliveryRequest {
    @NotBlank private String erpOrderId;
    private String transactionId;
    private Integer backorderPickingId;
    /** Exact delivery-note (picking) number to target — disambiguates multi-depot orders. */
    private String pickingRef;
}
