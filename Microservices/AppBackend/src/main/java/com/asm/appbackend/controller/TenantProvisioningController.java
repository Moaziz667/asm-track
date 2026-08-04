package com.asm.appbackend.controller;

import com.asm.tenant.jpa.TenantIterator;
import com.asm.tenant.TenantSchema;
import com.asm.appbackend.config.TenantSchemaProvisioner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Internal · Tenant Provisioning", description = "Service-to-service (SERVICE role) provisioning of "
        + "tenant database schemas. Called by the admin console after a company is created in Keycloak, and by "
        + "DB-less services that must enumerate tenants.")
@SecurityRequirement(name = "bearerAuth")
public class TenantProvisioningController {

    private final TenantSchemaProvisioner schemaProvisioner;
    private final TenantIterator tenantIterator;

    /**
     * Lists every provisioned tenant (company id per {@code company_<hex>} schema). Used by DB-less
     * services (the ERP adapter's inbound change poller) that must run work per tenant but can't read
     * the schema catalog themselves. Secured by {@code /internal/**} → SERVICE role.
     */
    @GetMapping
    @Operation(summary = "List provisioned tenants",
            description = "Returns the company id of every tenant that has a provisioned schema. Used by DB-less "
                    + "services (e.g. the ERP change poller) that run work per tenant.")
    @ApiResponse(responseCode = "200", description = "List of tenant company ids")
    public List<UUID> listTenants() {
        return tenantIterator.listProvisioned();
    }

    /**
     * Provisions a new tenant schema with all required tables.
     */
    @PostMapping("/{companyId}/provision")
    @Operation(summary = "Provision a tenant schema",
            description = "Creates the company's database schema and all required tables. Idempotent — safe to "
                    + "re-run for an already-provisioned tenant.")
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

    /**
     * Deprovisions a tenant schema (drops it). Use with extreme caution.
     */
    @DeleteMapping("/{companyId}")
    @Operation(summary = "Deprovision a tenant schema",
            description = "Drops the company's schema and all its data. Destructive and irreversible — intended "
                    + "for tenant offboarding/cleanup only.")
    @ApiResponse(responseCode = "200", description = "Schema dropped")
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
