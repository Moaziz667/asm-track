package com.asm.delivery.controller;

import com.asm.delivery.service.SystemSettingsService;
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
public class InternalErpSettingsController {

    private final SystemSettingsService settingsService;

    /**
     * Updates the active ERP provider for the current tenant.
     * The tenant context is resolved from the X-Company-Id header
     * (injected by the API Gateway, resolved by TenantContextFilter).
     */
    @PutMapping("/provider")
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
