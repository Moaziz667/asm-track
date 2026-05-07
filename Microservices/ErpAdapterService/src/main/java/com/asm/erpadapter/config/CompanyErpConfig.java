package com.asm.erpadapter.config;

/**
 * ERP connection config for a specific company, resolved at runtime from DeliveryMicroservice.
 */
public record CompanyErpConfig(
        String erpType,    // ODOO | DUX | NONE
        String apiUrl,
        String apiKey,     // Odoo password / DUX API key
        String dbName,
        String username,
        int    uid
) {}
