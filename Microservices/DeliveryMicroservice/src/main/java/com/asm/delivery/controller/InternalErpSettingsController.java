package com.asm.delivery.controller;

import com.asm.tenant.web.TenantContextFilter;

import com.asm.delivery.service.SystemSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal endpoint for ERP provider settings per tenant.
 * Called by AppBackend when ERP settings are saved via the admin UI,
 * so the delivery service routes to the correct ERP adapter per tenant.
 */
@RestController
@RequestMapping("/internal/erp")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Internal · ERP Provider", description = "Service-to-service (SERVICE role) sync of the active ERP "
        + "provider per tenant. Called by AppBackend when ERP settings are saved, so delivery routes to the "
        + "right adapter. Tenant resolved from the X-Company-Id header.")
@SecurityRequirement(name = "bearerAuth")
public class InternalErpSettingsController {

    private final SystemSettingsService settingsService;
    private final com.asm.delivery.erp.ErpLookupService erpLookupService;

    /**
     * Drops this tenant's cached ERP lookups.
     *
     * <p>Called when a business-field mapping changes. That mapping decides which ERP field each
     * imported value is read from, but the pending-order list is cached here for five minutes and
     * knew nothing about it — so an integrator would change a mapping, see the list unchanged, and
     * reasonably conclude the feature was broken. Eviction on write keeps the cache without letting
     * it lie: it happens rarely (someone editing configuration), so nothing is lost by it.
     */
    @PostMapping("/cache/evict")
    @Operation(summary = "[internal] Drop this tenant's cached ERP lookups",
            description = "Called after a mapping or settings change so the next read reflects it "
                    + "immediately instead of after the cache TTL.")
    @ApiResponse(responseCode = "204", description = "Cache dropped")
    public ResponseEntity<Void> evictCache() {
        erpLookupService.evictTenantCaches();
        return ResponseEntity.noContent().build();
    }

    /**
     * Updates the active ERP provider for the current tenant.
     * The tenant context is resolved from the X-Company-Id header
     * (injected by the API Gateway, resolved by TenantContextFilter).
     */
    @PutMapping("/provider")
    @Operation(summary = "[internal] Set the tenant's ERP provider",
            description = "Updates the active ERP provider for the tenant (from X-Company-Id). Body: {\"provider\": \"…\"}.")
    @ApiResponse(responseCode = "200", description = "Provider updated")
    public ResponseEntity<Map<String, String>> updateProvider(@RequestBody Map<String, String> body) {
        String provider = body.get("provider");
        if (provider == null || provider.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "provider is required"));
        }
        settingsService.upsert("erp.provider", provider.toLowerCase());
        log.info("ERP provider updated to {} for tenant", provider);
        return ResponseEntity.ok(Map.of("status", "OK", "provider", provider.toLowerCase()));
    }
}
