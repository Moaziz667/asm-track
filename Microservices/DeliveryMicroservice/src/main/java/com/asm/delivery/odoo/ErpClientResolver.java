package com.asm.delivery.odoo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ErpClientResolver {

    private final OdooClient odooClient;

    public String resolve(String name, String phone) {
        if (phone == null || phone.isBlank()) {
            log.warn("Cannot resolve ERP client: phone is missing");
            return null;
        }

        try {
            Integer existingPartnerId = odooClient.searchPartnerByPhone(phone);
            if (existingPartnerId != null) {
                return String.valueOf(existingPartnerId);
            }

            Integer createdPartnerId = odooClient.createPartner(name, phone);
            return createdPartnerId != null ? String.valueOf(createdPartnerId) : null;
        } catch (Exception e) {
            log.warn("Could not resolve ERP client for phone={} due to Odoo unavailability", phone, e);
            return null;
        }
    }
}