package com.asm.erpadapter.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The tenant's own selling company as held in the ERP (Odoo {@code res.company}).
 * Used to auto-populate "Informations sur l'Entreprise" so the BL header/footer and
 * public tracking reflect the real company without manual data entry.
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
    /** Base64-encoded company logo image as returned by Odoo res.company.logo (raw, no data-URI prefix). */
    private String logo;
}
