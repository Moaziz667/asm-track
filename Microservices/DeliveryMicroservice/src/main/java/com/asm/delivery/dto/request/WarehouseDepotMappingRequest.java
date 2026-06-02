package com.asm.delivery.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/** Upsert an ERP-warehouse → ASM-depot mapping. */
@Data
public class WarehouseDepotMappingRequest {
    @NotBlank
    private String warehouseCode;

    @NotNull
    private UUID depotId;

    /** Optional ERP provider tag (e.g. "odoo"); informational. */
    private String provider;
}
