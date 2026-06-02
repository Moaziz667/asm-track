package com.asm.erpadapter.dto.request;

import com.asm.erpadapter.dto.ErpPartialItemDTO;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/** Request to sync partial delivery to the ERP. */
@Data
public class SyncPartialDeliveryRequest {
    @NotBlank private String erpOrderId;
    private String transactionId;
    private List<ErpPartialItemDTO> items;
    /** Exact delivery-note (picking) number to target — disambiguates multi-depot orders. */
    private String pickingRef;
}
