package com.asm.driver.controller;

import com.asm.driver.config.TenantSchema;
import com.asm.driver.config.TenantSchemaProvisioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal endpoint for provisioning a new tenant schema in the driver database.
 * Called by the onboarding orchestration after the company is created in Keycloak.
 * Secured to the SERVICE role (see SecurityConfig).
 */
@RestController
@RequestMapping("/internal/tenants")
@RequiredArgsConstructor
@Slf4j
public class TenantProvisioningController {

    private final TenantSchemaProvisioner schemaProvisioner;

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

    @DeleteMapping("/{companyId}")
    public ResponseEntity<Map<String, String>> deprovision(@PathVariable UUID companyId) {
        log.warn("Deprovisioning tenant schema for company={}", companyId);
        try {
            schemaProvisioner.deprovision(companyId);
            return ResponseEntity.ok(Map.of("status", "OK", "message", "Tenant schema dropped"));
        } catch (Exception e) {
            log.error("Deprovisioning failed for company={}: {}", companyId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("status", "ERROR", "message", e.getMessage()));
        }
    }
}
