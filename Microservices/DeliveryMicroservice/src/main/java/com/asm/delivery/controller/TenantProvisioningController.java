package com.asm.delivery.controller;

import com.asm.delivery.config.TenantSchema;
import com.asm.delivery.config.TenantSchemaProvisioner;
import com.asm.delivery.web.ActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint for provisioning a new tenant schema.
 * Called by AppBackend after creating the company in Keycloak.
 */
@RestController
@RequestMapping("/internal/tenants")
@RequiredArgsConstructor
@Slf4j
public class TenantProvisioningController {

    private final TenantSchemaProvisioner schemaProvisioner;

    /**
     * Provisions a new tenant schema with Flyway migrations.
     * Called by AppBackend after the company is created in KC + the companies table.
     */
    @PostMapping("/{companyId}/provision")
    public ResponseEntity<Map<String, String>> provision(@PathVariable UUID companyId) {
        log.info("Provisioning tenant schema for company={}, actor={}", companyId, ActorContext.id());
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
        log.warn("Deprovisioning tenant schema for company={}, actor={}", companyId, ActorContext.id());
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
