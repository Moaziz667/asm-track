package com.asm.appbackend.controller;

import com.asm.appbackend.dto.SystemSettingsDto;
import com.asm.appbackend.entity.SystemSettings;
import com.asm.appbackend.repository.SystemSettingsRepository;
import com.asm.tenant.TenantContext;
import com.asm.appbackend.service.EncryptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;


import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Settings · ERP", description = "The caller company's ERP (Odoo) integration settings — connection "
        + "URL and credentials, stored encrypted per tenant. Management requires perm:settings:manage; the "
        + "internal read is service-to-service (SERVICE) for the poller.")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Missing or invalid access token"),
        @ApiResponse(responseCode = "403", description = "Caller lacks the required permission")
})
public class SettingsController {

    private final SystemSettingsRepository repository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;
    private final com.asm.appbackend.client.KeycloakAdminClient keycloakAdminClient;

    @org.springframework.beans.factory.annotation.Value("${allowed.erp.domains:}")
    private String allowedDomains;

    @Value("${delivery.service.url:http://delivery-service:8082}")
    private String deliveryUrl;

    @Value("${erp.adapter.url:http://erp-adapter:8088}")
    private String erpAdapterUrl;

    private void validateUrl(String url) {
        if (url == null || url.isBlank()) return;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) throw new SecurityException("SSRF Guard: Invalid host in URL");
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new SecurityException("SSRF Guard: Forbidden URL scheme: " + scheme);
            }
            if (allowedDomains != null && !allowedDomains.isBlank()) {
                java.util.List<String> allowed = java.util.Arrays.stream(allowedDomains.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                if (!allowed.isEmpty()) {
                    boolean match = allowed.stream().anyMatch(d -> host.equalsIgnoreCase(d) || host.endsWith("." + d));
                    if (!match) {
                        throw new SecurityException("SSRF Guard: Host '" + host + "' is not whitelisted for ERP integration");
                    }
                }
            }
        } catch (Exception e) {
            log.error("SSRF Guard block: URL '{}' failed validation: {}", url, e.getMessage());
            throw new SecurityException("SSRF Guard block: " + e.getMessage(), e);
        }
    }

    /**
     * Used by the React Admin Dashboard to fetch the current settings.
     * Passwords and sensitive keys are masked before returning.
     */
    @GetMapping("/erp")
    @Operation(summary = "Get ERP settings",
            description = "Returns the company's Odoo connection settings. Secrets (password/API key) are masked "
                    + "in the response — never returned in clear.")
    @ApiResponse(responseCode = "200", description = "ERP settings (secrets masked)")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<SystemSettingsDto> getErpSettings() {
        SystemSettings settings = repository.findById("SINGLETON").orElse(null);
        if (settings == null) {
            return ResponseEntity.ok(new SystemSettingsDto("NONE", null));
        }

        Map<String, Object> configMap = null;
        if (settings.getErpConfiguration() != null) {
            String decryptedJson = encryptionService.decrypt(settings.getErpConfiguration());
            try {
                configMap = objectMapper.readValue(decryptedJson, new TypeReference<Map<String, Object>>() {});
                // Mask sensitive fields for the frontend
                if (configMap.containsKey("password")) {
                    configMap.put("password", "********");
                }
                if (configMap.containsKey("apiKey")) {
                    configMap.put("apiKey", "********");
                }
                if (configMap.containsKey("apiSecret")) {
                    configMap.put("apiSecret", "********");
                }
            } catch (JsonProcessingException e) {
                log.error("Failed to parse decrypted ERP config", e);
            }
        }

        SystemSettingsDto out = new SystemSettingsDto(settings.getActiveErpProvider(), configMap);
        applyStatus(out, settings);
        return ResponseEntity.ok(out);
    }

    /** Copy the persisted connection-lifecycle fields onto an outgoing DTO. */
    private void applyStatus(SystemSettingsDto dto, SystemSettings s) {
        dto.setConnectionStatus(s.getConnectionStatus());
        dto.setLastTestedAt(s.getLastTestedAt());
        dto.setLastConnectedAt(s.getLastConnectedAt());
        dto.setLastError(s.getLastError());
        dto.setLastTestUid(s.getLastTestUid());
    }

    /**
     * Used by the React Admin Dashboard to update the ERP settings.
     * The JSON payload is encrypted before saving.
     */
    @PutMapping("/erp")
    @Operation(summary = "Update ERP settings",
            description = "Saves the company's Odoo URL and credentials (encrypted at rest). The URL host must be "
                    + "in the server's ERP allow-list. Omitted secret fields keep their stored value.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Settings saved"),
            @ApiResponse(responseCode = "400", description = "Invalid URL or host not allow-listed")
    })
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Void> updateErpSettings(@RequestBody SystemSettingsDto dto) {
        SystemSettings settings = repository.findById("SINGLETON").orElse(new SystemSettings());
        settings.setId("SINGLETON");

        String oldProvider = settings.getActiveErpProvider();
        String oldEncrypted = settings.getErpConfiguration();
        settings.setActiveErpProvider(dto.getActiveErpProvider());

        if (dto.getErpConfiguration() != null) {
            try {
                // If the frontend sends masked passwords, we need to ignore them and keep the old password
                Map<String, Object> newConfig = objectMapper.convertValue(dto.getErpConfiguration(), new TypeReference<Map<String, Object>>() {});

                if (oldEncrypted != null) {
                    String decryptedOldJson = encryptionService.decrypt(oldEncrypted);
                    Map<String, Object> oldConfig = objectMapper.readValue(decryptedOldJson, new TypeReference<Map<String, Object>>() {});

                    // Restore original password if frontend sent mask
                    if ("********".equals(newConfig.get("password")) && oldConfig.containsKey("password")) {
                        newConfig.put("password", oldConfig.get("password"));
                    }
                    if ("********".equals(newConfig.get("apiSecret")) && oldConfig.containsKey("apiSecret")) {
                        newConfig.put("apiSecret", oldConfig.get("apiSecret"));
                    }
                    if ("********".equals(newConfig.get("apiKey")) && oldConfig.containsKey("apiKey")) {
                        newConfig.put("apiKey", oldConfig.get("apiKey"));
                    }
                }

                String jsonToEncrypt = objectMapper.writeValueAsString(newConfig);
                String encryptedJson = encryptionService.encrypt(jsonToEncrypt);
                settings.setErpConfiguration(encryptedJson);
            } catch (Exception e) {
                log.error("Failed to process new ERP config", e);
                return ResponseEntity.badRequest().build();
            }
        } else {
            settings.setErpConfiguration(null);
        }

        // ── Connection lifecycle on save ──────────────────────────────────────
        // Saving config never proves it works. A CONNECTED status only comes from a passing
        // Test. So: provider NONE → NOT_CONFIGURED; otherwise, if the provider or the
        // credentials actually changed, drop back to CONFIGURED (untested) and clear the old
        // green state — this is the "you changed it, re-test" invalidation. If nothing
        // material changed, keep whatever status we had (e.g. stay CONNECTED).
        String newProvider = settings.getActiveErpProvider();
        boolean credsChanged = !java.util.Objects.equals(oldEncrypted, settings.getErpConfiguration());
        boolean providerChanged = !java.util.Objects.equals(oldProvider, newProvider);

        if (newProvider == null || "NONE".equalsIgnoreCase(newProvider) || settings.getErpConfiguration() == null) {
            settings.setConnectionStatus("NOT_CONFIGURED");
            settings.setLastError(null);
            settings.setLastTestUid(null);
            settings.setLastConnectedAt(null);
        } else if (providerChanged || credsChanged || settings.getConnectionStatus() == null
                || "NOT_CONFIGURED".equals(settings.getConnectionStatus())) {
            settings.setConnectionStatus("CONFIGURED");
            settings.setLastError(null);
            settings.setLastTestUid(null);
            settings.setLastConnectedAt(null);
        }

        repository.save(settings);

        // Push the active ERP provider to delivery-service so its adapter
        // routes to the correct ERP per tenant (not hardcoded "odoo").
        pushProviderToDelivery(newProvider);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/erp/test")
    @Operation(summary = "Test ERP settings (from request body)",
            description = "Attempts an Odoo connection with the credentials in the body (without saving them) and "
                    + "reports whether the handshake succeeds. Use before saving to validate credentials.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Connection result (status message)"),
            @ApiResponse(responseCode = "400", description = "Invalid URL or host not allow-listed")
    })
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Map<String, String>> testErpSettings(@RequestBody SystemSettingsDto dto) {
        if ("DUX".equals(dto.getActiveErpProvider())) {
            // Placeholder for next week's DUX adapter implementation
            return ResponseEntity.ok(Map.of("status", "success", "message", "DUX adapter testing will be implemented next week."));
        }

        boolean isErpNext = "ERPNEXT".equals(dto.getActiveErpProvider());
        if ((!"ODOO".equals(dto.getActiveErpProvider()) && !isErpNext) || dto.getErpConfiguration() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unsupported or missing provider config"));
        }

        Map<String, Object> config = objectMapper.convertValue(dto.getErpConfiguration(), new TypeReference<>() {});

        // Masked secrets ("********") mean "unchanged" — restore the real value from the stored
        // record, BUT only field-by-field. A typed (non-masked) secret is used as-is and never
        // overwritten. This guarantees a wrong key the admin actually typed is what gets tested.
        SystemSettings stored = repository.findById("SINGLETON").orElse(null);
        Map<String, Object> oldConfig = decryptStoredConfig(stored);
        restoreMaskedSecret(config, oldConfig, "apiKey");
        restoreMaskedSecret(config, oldConfig, "apiSecret");
        restoreMaskedSecret(config, oldConfig, "password");

        String providerType = isErpNext ? "erpnext" : "odoo";
        return proxyTestAuth(config, providerType);
    }

    /**
     * Test the credentials EXACTLY as persisted in the DB — no client payload, so there is no
     * mask/restore ambiguity. Used by the "Save & test" chain: the PUT has already written the
     * admin's real values, so testing the stored config proves those exact values work (or not).
     */
    @PostMapping("/erp/test-stored")
    @Operation(summary = "Test the stored ERP settings",
            description = "Attempts an Odoo connection using the credentials already saved for the company. Use to "
                    + "re-check a previously configured connection without re-entering the password.")
    @ApiResponse(responseCode = "200", description = "Connection result (status message)")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Map<String, String>> testStoredErpSettings() {
        SystemSettings stored = repository.findById("SINGLETON").orElse(null);
        if (stored == null || stored.getErpConfiguration() == null
                || stored.getActiveErpProvider() == null || "NONE".equalsIgnoreCase(stored.getActiveErpProvider())) {
            return ResponseEntity.badRequest().body(Map.of("error", "No ERP configuration saved"));
        }
        if ("DUX".equalsIgnoreCase(stored.getActiveErpProvider())) {
            return ResponseEntity.ok(Map.of("status", "success", "message", "DUX adapter testing will be implemented next week."));
        }
        if ("ERPNEXT".equalsIgnoreCase(stored.getActiveErpProvider())) {
            return proxyTestAuth(decryptStoredConfig(stored), "erpnext");
        }
        return proxyTestAuth(decryptStoredConfig(stored), "odoo");
    }

    /** Decrypt the stored ERP config JSON into a map (empty map if none). */
    private Map<String, Object> decryptStoredConfig(SystemSettings stored) {
        if (stored == null || stored.getErpConfiguration() == null) return new HashMap<>();
        try {
            String json = encryptionService.decrypt(stored.getErpConfiguration());
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not decrypt stored ERP config: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /** Replace a masked ("********") secret with the stored value; leave a real typed value untouched. */
    private void restoreMaskedSecret(Map<String, Object> config, Map<String, Object> oldConfig, String key) {
        if ("********".equals(config.get(key)) && oldConfig.containsKey(key)) {
            config.put(key, oldConfig.get(key));
        }
    }

    /**
     * Proxy the auth test to ErpAdapterService's {@code POST /api/v1/erp/test-auth} endpoint,
     * which uses the adapter's own HTTP client (timeouts, SSRF guard) — the same path that
     * real sync operations use. Eliminates the duplicated inline RestClient logic.
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, String>> proxyTestAuth(Map<String, Object> config, String providerType) {
        try {
            java.util.UUID companyId = TenantContext.get();
            String token = getServiceToken();

            // Build the config map the adapter expects
            Map<String, String> adapterConfig = new HashMap<>();
            adapterConfig.put("type", providerType);
            for (String key : new String[]{"url", "db", "login", "apiKey", "password", "apiSecret"}) {
                Object val = config.get(key);
                if (val != null && !"********".equals(String.valueOf(val))) {
                    adapterConfig.put(key, String.valueOf(val));
                }
            }

            Map<String, String> result = restClientBuilder.build()
                    .post()
                    .uri(erpAdapterUrl + "/api/v1/erp/test-auth")
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.set("Accept", "application/json");
                        if (companyId != null) h.set("X-Company-Id", companyId.toString());
                        if (!token.isEmpty()) h.set("Authorization", "Bearer " + token);
                    })
                    .body(adapterConfig)
                    .retrieve()
                    .body(Map.class);

            boolean success = result != null && "success".equals(result.get("status"));
            String uid = result != null ? result.get("uid") : null;
            String error = result != null ? result.get("error") : null;

            if (success) {
                // Valid credentials are necessary but not sufficient. Marking a tenant CONNECTED on
                // authentication alone is what let instances go live against an ERP missing a
                // capability the write path needs: the gap only surfaced later, as a delivery the
                // driver had already made dead-lettering in the outbox. Certify the integration
                // contract here, while an admin is watching and can act on it.
                String incompatibility = certifyContract(companyId, token);
                if (incompatibility != null) {
                    persistTestResult(false, uid, incompatibility);
                    return ResponseEntity.badRequest().body(Map.of(
                            "status", "incompatible",
                            "error", incompatibility));
                }
                persistTestResult(true, uid, null);
                return ResponseEntity.ok(result);
            } else {
                persistTestResult(false, null, error);
                return ResponseEntity.badRequest().body(result != null ? result : Map.of("error", "No response from adapter"));
            }
        } catch (Exception e) {
            log.warn("ERP test-auth proxy failed for {}: {}", providerType, e.getMessage());
            persistTestResult(false, null, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Runs the conformance probe and reports why the instance cannot be certified, or {@code null}
     * when it can.
     *
     * <p>Only a {@code NO_GO} blocks: that means a REQUIRED capability is genuinely absent, so the
     * write path would fail on a real delivery. {@code DEGRADED} is allowed through — the adapter has
     * a working alternative for every RECOMMENDED capability, and refusing those would reject
     * perfectly usable ERP versions.
     *
     * <p>A probe that cannot run is deliberately <b>not</b> treated as incompatible. Its own failure
     * is evidence about the probe, not about the ERP, and blocking on it would lock an admin out of a
     * working integration over a transient hiccup.
     */
    @SuppressWarnings("unchecked")
    private String certifyContract(java.util.UUID companyId, String token) {
        try {
            Map<String, Object> report = restClientBuilder.build()
                    .get()
                    .uri(erpAdapterUrl + "/api/v1/erp/conformance")
                    .headers(h -> {
                        if (companyId != null) h.set("X-Company-Id", companyId.toString());
                        if (!token.isEmpty()) h.set("Authorization", "Bearer " + token);
                    })
                    .retrieve()
                    .body(Map.class);
            if (report == null || !"NO_GO".equals(String.valueOf(report.get("verdict")))) return null;

            java.util.List<String> blocking = new java.util.ArrayList<>();
            Object checks = report.get("checks");
            if (checks instanceof java.util.List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> c)) continue;
                    if ("REQUIRED".equals(String.valueOf(c.get("severity")))
                            && "MISSING".equals(String.valueOf(c.get("status")))) {
                        blocking.add(String.valueOf(c.get("name")));
                    }
                }
            }
            log.warn("ERP certification NO_GO for tenant {} — missing REQUIRED: {}", companyId, blocking);
            return "L'ERP ne fournit pas tout ce dont la synchronisation a besoin : "
                    + (blocking.isEmpty() ? "voir le rapport de conformité" : String.join(", ", blocking))
                    + ". Connexion refusée pour éviter des livraisons non synchronisées.";
        } catch (Exception e) {
            log.warn("ERP certification could not run for tenant {} ({}) — not treated as incompatible",
                    companyId, e.getMessage());
            return null;
        }
    }

    /**
     * Record the outcome of a connection test on the singleton settings row, so the
     * CONNECTED/ERROR state survives reloads and is visible to the Import page. Best-effort:
     * a persistence hiccup must not change the HTTP result the admin sees.
     */
    private void persistTestResult(boolean ok, String uid, String error) {
        try {
            SystemSettings settings = repository.findById("SINGLETON").orElse(null);
            if (settings == null) return; // nothing saved yet — a pure pre-save probe
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            settings.setLastTestedAt(now);
            if (ok) {
                settings.setConnectionStatus("CONNECTED");
                settings.setLastConnectedAt(now);
                settings.setLastTestUid(uid);
                settings.setLastError(null);
            } else {
                settings.setConnectionStatus("ERROR");
                settings.setLastError(error);
            }
            repository.save(settings);
        } catch (Exception ex) {
            log.warn("Could not persist ERP test result: {}", ex.getMessage());
        }
    }

    /**
     * Certifies the tenant's live ERP instance against the ASM integration contract — the "drytest".
     * Proxies the read-only conformance probe on ErpAdapterService (service-to-service, SERVICE role) so
     * the admin never calls the internal service directly. Returns the GO/DEGRADED/NO_GO report, or 204
     * when no ERP is configured for the tenant.
     */
    @GetMapping("/erp/conformance")
    @Operation(summary = "Certify the ERP instance (drytest)",
            description = "Runs the read-only conformance probe against the company's configured ERP and "
                    + "returns a GO/DEGRADED/NO_GO report with the detected version and per-capability "
                    + "results. 204 when no ERP is configured.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conformance report"),
            @ApiResponse(responseCode = "204", description = "No ERP configured"),
            @ApiResponse(responseCode = "502", description = "ERP adapter unreachable")
    })
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Object> getErpConformance(
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        java.util.UUID companyId = TenantContext.get();
        if (companyId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        try {
            String token = getServiceToken();
            // Passed through rather than absorbed: the adapter owns the cache, and the re-check
            // button means nothing if the proxy answers it from a stale copy of its own.
            Object report = restClientBuilder.build()
                    .get()
                    .uri(erpAdapterUrl + "/api/v1/erp/conformance?forceRefresh=" + forceRefresh)
                    .headers(h -> {
                        h.set("X-Company-Id", companyId.toString());
                        if (!token.isEmpty()) h.set("Authorization", "Bearer " + token);
                    })
                    .retrieve()
                    .body(Object.class);
            return report != null ? ResponseEntity.ok(report) : ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.warn("ERP conformance probe failed for tenant {}: {}", companyId, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Business field mapping ────────────────────────────────────────────────────────────────────
    // Where, in this customer's ERP, each piece of business data lives. Proxied to ErpAdapterService
    // for the same reason as the conformance probe: the adapter is not routed through the gateway, and
    // the permission check belongs here where the caller's token is.
    //
    // Kept apart from /erp/mappings (capability overrides) on purpose. A wrong capability mapping
    // breaks stock; a wrong field mapping shows a wrong label. Separate endpoints let the UI be
    // separate screens, so nobody corrupts quantities while relabelling a customer reference.

    @GetMapping("/erp/field-mappings")
    @Operation(summary = "List this company's business-field mappings")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Object> listFieldMappings(
            @RequestParam(required = false) String provider) {
        // Not defaulted here: the adapter knows this company's ERP, and a default of "odoo" listed
        // the wrong provider's mappings for everyone else — an ERPNext screen simply looked empty.
        return proxyToAdapter("/api/v1/erp/field-mappings" + providerQuery(provider), HttpMethod.GET, null);
    }

    /** Forward {@code provider} only when the caller named one, so the adapter can use the tenant's. */
    private static String providerQuery(String provider) {
        return provider == null || provider.isBlank() ? "" : "?provider=" + provider;
    }

    /** The ASM vocabulary — one row per mappable field, so the screen does not hardcode the list. */
    @GetMapping("/erp/field-mappings/canonical-fields")
    @Operation(summary = "The ASM business fields that can be mapped")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Object> canonicalFields() {
        return proxyToAdapter("/api/v1/erp/field-mappings/canonical-fields", HttpMethod.GET, null);
    }

    /**
     * Which ERP documents each scope may be mapped from, for this company's provider.
     *
     * <p>Served rather than known by the screen: the picker stores the exact path shape the resolver
     * will later parse, and the two disagreeing produces a mapping that resolves to nothing.
     */
    @GetMapping("/erp/field-mappings/scopes")
    @Operation(summary = "The ERP documents each canonical scope may be mapped from")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Object> mappingScopes() {
        return proxyToAdapter("/api/v1/erp/field-mappings/scopes", HttpMethod.GET, null);
    }

    /**
     * The customer's own ERP fields, for the dropdown — their {@code x_*} fields included, which are
     * exactly the ones no automatic detection could have found.
     */
    @GetMapping("/erp/field-mappings/available-fields")
    @Operation(summary = "Fields available on this company's ERP, for the mapping dropdown")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Object> availableFields(@RequestParam(required = false) String model) {
        String path = "/api/v1/erp/field-mappings/available-fields"
                + (model != null && !model.isBlank() ? "?model=" + model : "");
        return proxyToAdapter(path, HttpMethod.GET, null);
    }

    @PostMapping("/erp/field-mappings")
    @Operation(summary = "Create or replace one business-field mapping")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Object> upsertFieldMapping(@RequestBody Map<String, Object> body) {
        return proxyToAdapter("/api/v1/erp/field-mappings", HttpMethod.POST, body);
    }

    /** Removing a mapping restores the shipped default; it does not blank the field. */
    @DeleteMapping("/erp/field-mappings/{canonicalField}")
    @Operation(summary = "Remove a mapping and fall back to the default")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Object> deleteFieldMapping(
            @PathVariable String canonicalField,
            @RequestParam(required = false) String provider) {
        return proxyToAdapter("/api/v1/erp/field-mappings/" + canonicalField + providerQuery(provider),
                HttpMethod.DELETE, null);
    }

    @DeleteMapping("/erp/field-mappings/by-id/{id}")
    @Operation(summary = "Remove a custom (non-canonical) mapping by id")
    @PreAuthorize("hasAuthority('perm:settings:manage')")
    public ResponseEntity<Object> deleteFieldMappingById(@PathVariable Long id) {
        return proxyToAdapter("/api/v1/erp/field-mappings/by-id/" + id, HttpMethod.DELETE, null);
    }

    /**
     * Forward a field-mapping call to ErpAdapterService under this tenant.
     *
     * <p>The adapter's 400s carry a message written for the integrator ("Chemin trop profond…"), so
     * they are passed through rather than flattened into a generic error — the whole point of the
     * screen is that the person configuring it can fix their own mistake.
     */
    private ResponseEntity<Object> proxyToAdapter(String path, HttpMethod method, Object body) {
        java.util.UUID companyId = TenantContext.get();
        if (companyId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No tenant context"));
        }
        try {
            String token = getServiceToken();
            RestClient.RequestBodySpec spec = restClientBuilder.build()
                    .method(method)
                    .uri(erpAdapterUrl + path)
                    .headers(h -> {
                        h.set("X-Company-Id", companyId.toString());
                        h.setContentType(MediaType.APPLICATION_JSON);
                        if (!token.isEmpty()) h.set("Authorization", "Bearer " + token);
                    });
            if (body != null) spec.body(body);

            ResponseEntity<Object> res = spec.retrieve()
                    .onStatus(s -> s.value() == 400, (req, rsp) -> { })   // let the adapter's message through
                    .toEntity(Object.class);

            // A mapping change alters which ERP field every imported value is read from, but
            // delivery-service caches ERP lookups for five minutes and knows nothing about it. Without
            // this the integrator edits a mapping, sees the list unchanged, and concludes it is broken.
            if (method != HttpMethod.GET && res.getStatusCode().is2xxSuccessful()) {
                evictDeliveryErpCache(companyId, token);
            }
            return ResponseEntity.status(res.getStatusCode()).body(res.getBody());
        } catch (Exception e) {
            log.warn("Field mapping call {} failed for tenant {}: {}", path, companyId, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Tell delivery-service to drop this tenant's cached ERP lookups.
     *
     * <p>Best-effort on purpose: the mapping is already saved, and a cache that clears itself in five
     * minutes anyway must not turn a successful write into a failed request. A warning is enough for
     * support to explain a stale list.
     */
    private void evictDeliveryErpCache(java.util.UUID companyId, String token) {
        try {
            restClientBuilder.build()
                    .post()
                    .uri(deliveryUrl + "/internal/erp/cache/evict")
                    .headers(h -> {
                        h.set("X-Company-Id", companyId.toString());
                        if (!token.isEmpty()) h.set("Authorization", "Bearer " + token);
                    })
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Could not evict delivery ERP cache for tenant {} — the list may be stale for up "
                    + "to the cache TTL: {}", companyId, e.getMessage());
        }
    }

    /**
     * INTERNAL ENDPOINT: Lightweight provider-only read. Used by delivery-service's
     * {@code TenantErpProviderResolver} to resolve the active ERP provider per tenant
     * without fetching decrypted credentials.
     */
    @GetMapping("/internal/erp/provider")
    @Operation(summary = "[internal] Get the active ERP provider name",
            description = "Service-to-service only (SERVICE role). Returns just the provider key "
                    + "(e.g. \"odoo\", \"erpnext\") — no secrets, no config.")
    @ApiResponse(responseCode = "200", description = "Provider name")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Map<String, String>> getInternalErpProvider() {
        SystemSettings settings = repository.findById("SINGLETON").orElse(null);
        String provider = (settings != null && settings.getActiveErpProvider() != null)
                ? settings.getActiveErpProvider().toLowerCase() : "none";
        return ResponseEntity.ok(Map.of("provider", provider));
    }

    /**
     * INTERNAL ENDPOINT: Used by ErpAdapterService to fetch the unmasked, decrypted credentials.
     * This endpoint must be secured via internal network rules or machine-to-machine OAuth tokens.
     */
    @GetMapping("/internal/erp")
    @Operation(summary = "[internal] Get ERP settings with decrypted secrets",
            description = "Service-to-service only (SERVICE role). Returns the company's ERP settings WITH "
                    + "decrypted credentials, for the ERP poller/adapter. Never exposed to end users.")
    @ApiResponse(responseCode = "200", description = "ERP settings with decrypted secrets")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<SystemSettingsDto> getInternalErpSettings() {
        SystemSettings settings = repository.findById("SINGLETON").orElse(null);
        if (settings == null) {
            return ResponseEntity.ok(new SystemSettingsDto("NONE", null));
        }

        Map<String, Object> configMap = null;
        if (settings.getErpConfiguration() != null) {
            String decryptedJson = encryptionService.decrypt(settings.getErpConfiguration());
            try {
                configMap = objectMapper.readValue(decryptedJson, new TypeReference<Map<String, Object>>() {});
                // NOTE: We DO NOT mask the passwords here! ErpAdapterService needs them.
            } catch (JsonProcessingException e) {
                log.error("Failed to parse decrypted ERP config", e);
            }
        }

        SystemSettingsDto out = new SystemSettingsDto(settings.getActiveErpProvider(), configMap);
        // Propagate the verified connection status so the adapter can refuse to pull orders
        // with credentials that aren't CONNECTED.
        out.setConnectionStatus(settings.getConnectionStatus());
        return ResponseEntity.ok(out);
    }

    /**
     * Push the active ERP provider to delivery-service so it routes to the correct
     * ERP adapter per tenant (not the hardcoded "odoo" default).
     * Best-effort: failure is logged but doesn't block the settings save.
     */
    private void pushProviderToDelivery(String provider) {
        if (provider == null || provider.isBlank() || "NONE".equalsIgnoreCase(provider)) return;
        java.util.UUID companyId = TenantContext.get();
        if (companyId == null) {
            log.debug("No tenant context — skipping ERP provider push to delivery-service");
            return;
        }
        try {
            String token = getServiceToken();
            Map<String, String> body = Map.of("provider", provider.toLowerCase());

            restClientBuilder.build()
                .post()
                .uri(deliveryUrl + "/internal/erp/provider")
                .headers(h -> {
                    h.setContentType(MediaType.APPLICATION_JSON);
                    h.set("X-Company-Id", companyId.toString());
                    if (!token.isEmpty()) h.set("Authorization", "Bearer " + token);
                })
                .body(body)
                .retrieve()
                .toBodilessEntity();

            log.info("Pushed ERP provider '{}' to delivery-service for tenant {}", provider, companyId);
        } catch (Exception e) {
            log.warn("Failed to push ERP provider to delivery-service: {}", e.getMessage());
        }
    }

    /**
     * SERVICE client_credentials token from the <b>asm</b> realm (managed/cached by Spring Security via
     * {@link com.asm.appbackend.client.KeycloakAdminClient}). The internal services (delivery, erp-adapter)
     * validate JWTs against the asm realm, so a master-realm token is rejected 401 — this delegates to the
     * one, proven asm-realm token used across onboarding/IAM.
     */
    private String getServiceToken() {
        try {
            return keycloakAdminClient.getServiceToken();
        } catch (Exception e) {
            log.warn("Failed to get SERVICE token (asm realm): {}", e.getMessage());
            return "";
        }
    }
}
