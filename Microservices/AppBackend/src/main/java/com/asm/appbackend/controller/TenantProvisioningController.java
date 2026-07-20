package com.asm.appbackend.controller;

import com.asm.appbackend.config.TenantIterator;
import com.asm.appbackend.config.TenantSchema;
import com.asm.appbackend.config.TenantSchemaProvisioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Provisioning endpoint for new tenants.
 * Called by admin console after creating the company in Keycloak.
 */
@RestController
@RequestMapping("/internal/tenants")
@RequiredArgsConstructor
@Slf4j
public class TenantProvisioningController {

    private final TenantSchemaProvisioner schemaProvisioner;
    private final TenantIterator tenantIterator;

    /**
     * Lists every provisioned tenant (company id per {@code company_<hex>} schema). Used by DB-less
     * services (the ERP adapter's inbound change poller) that must run work per tenant but can't read
     * the schema catalog themselves. Secured by {@code /internal/**} → SERVICE role.
     */
    @GetMapping
    public List<UUID> listTenants() {
        return tenantIterator.listProvisioned();
    }

    /**
     * Provisions a new tenant schema with all required tables.
     */
    @PostMapping("/{companyId}/provision")
    public ResponseEntity<Map<String, String>> provision(@PathVariable UUID companyId) {
        log.info("Provisioning tenant schema for company={}", companyId);
        try {
            schemaProvisioner.provision(companyId);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "schema", TenantSchema.schemaFor(companyId),
                    "message", "Tenant schema provisioned successfully"
            ));
        } catch (Exception e) {
            log.error("Provisioning failed for company={}: {}", companyId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Deprovisions a tenant schema (drops it). Use with extreme caution.
     */
    @DeleteMapping("/{companyId}")
    public ResponseEntity<Map<String, String>> deprovision(@PathVariable UUID companyId) {
        log.warn("Deprovisioning tenant schema for company={}", companyId);
        try {
            schemaProvisioner.deprovision(companyId);
            return ResponseEntity.ok(Map.of(
                    "status", "OK",
                    "message", "Tenant schema dropped"
            ));
        } catch (Exception e) {
            log.error("Deprovisioning failed for company={}: {}", companyId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "message", e.getMessage()
            ));
        }
    }
}
