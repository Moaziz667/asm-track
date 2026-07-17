package com.asm.delivery.erp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Canonical selling-company record pulled from the ERP — provider-neutral.
 * Used to auto-populate the tenant's company info (branding + legal fields) at onboarding.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpCompanyDTO {
    private String name;
    private String address;
    private String city;
    private String phone;
    private String email;
    private String vat;
    private String website;
    private String logo;
}
