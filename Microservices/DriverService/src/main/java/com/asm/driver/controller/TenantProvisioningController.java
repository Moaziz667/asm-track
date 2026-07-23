package com.asm.driver.controller;

import com.asm.driver.config.TenantSchema;
import com.asm.driver.config.TenantSchemaProvisioner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Internal · Tenant Provisioning (Driver DB)", description = "Service-to-service (SERVICE role) "
        + "provisioning of the driver database schema for a tenant. Called by onboarding after the company is "
        + "created in Keycloak.")
@SecurityRequirement(name = "bearerAuth")
public class TenantProvisioningController {

    private final TenantSchemaProvisioner schemaProvisioner;

    @PostMapping("/{companyId}/provision")
    @Operation(summary = "Provision the driver schema for a tenant",
            description = "Creates the company's driver-database schema and tables. Idempotent.")
    @ApiResponse(responseCode = "200", description = "Schema provisioned")
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
    @Operation(summary = "Deprovision the driver schema for a tenant",
            description = "Drops the company's driver schema and all its data. Destructive and irreversible.")
    @ApiResponse(responseCode = "200", description = "Schema dropped")
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
