package com.asm.delivery.erp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canonical warehouse (source depot) pulled from the ERP — provider-neutral.
 * The {@code code} is the stable key ASM maps to a depot; coordinates are null when the ERP
 * doesn't carry them (ASM geocodes the address as a fallback).
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
