package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An ERP warehouse (Odoo {@code stock.warehouse}) used as a source depot for multi-depot routing.
 * The {@code code} is the stable key ASM maps to a depot row; coordinates come from the warehouse's
 * address partner when present (ASM geocodes the address as a fallback when they are null).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpWarehouseDTO {
    private String erpWarehouseId;
    private String code;
    private String name;
    private String address;
    private String city;
    private Double latitude;
    private Double longitude;
}
