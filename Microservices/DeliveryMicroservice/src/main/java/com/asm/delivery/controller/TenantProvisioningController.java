package com.asm.delivery.controller;

import com.asm.tenant.TenantSchema;
import com.asm.delivery.config.TenantSchemaProvisioner;
import com.asm.delivery.web.ActorContext;
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
 * Internal endpoint for provisioning a new tenant schema.
 * Called by AppBackend after creating the company in Keycloak.
 */
@RestController
@RequestMapping("/internal/tenants")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Internal · Tenant Provisioning (Delivery DB)", description = "Service-to-service (SERVICE role) "
        + "provisioning of the delivery database schema for a tenant. Called by AppBackend after the company is "
        + "created in Keycloak.")
@SecurityRequirement(name = "bearerAuth")
public class TenantProvisioningController {

    private final TenantSchemaProvisioner schemaProvisioner;

    /**
     * Provisions a new tenant schema with Flyway migrations.
     * Called by AppBackend after the company is created in KC + the companies table.
     */
    @PostMapping("/{companyId}/provision")
    @Operation(summary = "Provision the delivery schema for a tenant",
            description = "Creates the company's delivery-database schema and runs Flyway migrations. Idempotent.")
    @ApiResponse(responseCode = "200", description = "Schema provisioned")
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
    @Operation(summary = "Deprovision the delivery schema for a tenant",
            description = "Drops the company's delivery schema and all its data. Destructive and irreversible.")
    @ApiResponse(responseCode = "200", description = "Schema dropped")
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
