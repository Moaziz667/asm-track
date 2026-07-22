package com.asm.appbackend.controller;

import com.asm.appbackend.dto.SystemSettingsDto;
import com.asm.appbackend.entity.SystemSettings;
import com.asm.appbackend.repository.SystemSettingsRepository;
import com.asm.appbackend.security.TenantContext;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;


import java.util.HashMap;
import java.util.List;
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

    @org.springframework.beans.factory.annotation.Value("${allowed.erp.domains:}")
    private String allowedDomains;

    @Value("${delivery.service.url:http://delivery-service:8082}")
    private String deliveryUrl;

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

        return isErpNext ? runErpNextTest(config) : runOdooTest(config);
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
            return runErpNextTest(decryptStoredConfig(stored));
        }
        return runOdooTest(decryptStoredConfig(stored));
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

    /** Authenticate the given Odoo config and persist the CONNECTED/ERROR result. */
    private ResponseEntity<Map<String, String>> runOdooTest(Map<String, Object> config) {
        try {
            String url = String.valueOf(config.get("url"));
            validateUrl(url);
            String db = String.valueOf(config.get("db"));
            String login = config.get("login") != null ? String.valueOf(config.get("login")) : "";
            // Odoo accepts an API key wherever a password is expected — prefer it, fall back to password.
            Object apiKey = config.get("apiKey");
            String secret = (apiKey != null && !String.valueOf(apiKey).isBlank())
                    ? String.valueOf(apiKey) : String.valueOf(config.get("password"));

            // Validate by authenticating: common.authenticate returns the numeric uid (or false on
            // bad credentials). A uid > 0 proves the db + login + API key triple is valid. Pure JSON-RPC.
            Map<String, Object> params = new HashMap<>();
            params.put("service", "common");
            params.put("method", "authenticate");
            params.put("args", List.of(db, login, secret, Map.of()));

            Map<String, Object> body = new HashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("method", "call");
            body.put("params", params);

            Map response = org.springframework.web.client.RestClient.create()
                    .post().uri(url)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            // Odoo returns {"result": <uid>} on success, {"result": false} on bad creds, or an
            // {"error": ...} envelope on a server/db error. Treat anything but a positive uid as failure.
            Object errorEnvelope = response != null ? response.get("error") : null;
            Object result = response != null ? response.get("result") : null;
            int uid = result instanceof Number ? ((Number) result).intValue() : -1;

            if (errorEnvelope == null && uid > 0) {
                persistTestResult(true, String.valueOf(uid), null);
                return ResponseEntity.ok(Map.of("status", "success", "uid", String.valueOf(uid)));
            }

            String reason = errorEnvelope != null
                    ? "Odoo error: " + extractOdooError(errorEnvelope)
                    : "Authentication failed — wrong database, login, or API key.";
            persistTestResult(false, null, reason);
            return ResponseEntity.badRequest().body(Map.of("error", reason));

        } catch (Exception e) {
            log.warn("ERP Test Connection failed: {}", e.getMessage());
            persistTestResult(false, null, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Authenticate an ERPNext config and persist the CONNECTED/ERROR result. ERPNext uses token auth
     * ({@code Authorization: token <apiKey>:<apiSecret>}); we validate by calling
     * {@code frappe.auth.get_logged_user}, which returns the user bound to the key — a plain, cheap,
     * side-effect-free probe (the ERPNext equivalent of Odoo's {@code common.authenticate}).
     */
    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map<String, String>> runErpNextTest(Map<String, Object> config) {
        try {
            String url = String.valueOf(config.get("url"));
            validateUrl(url);
            String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            String apiKey = config.get("apiKey") != null ? String.valueOf(config.get("apiKey")) : "";
            String apiSecret = config.get("apiSecret") != null ? String.valueOf(config.get("apiSecret")) : "";
            if (apiKey.isBlank() || apiSecret.isBlank()) {
                persistTestResult(false, null, "Missing API key or secret.");
                return ResponseEntity.badRequest().body(Map.of("error", "Missing API key or secret."));
            }

            Map response = org.springframework.web.client.RestClient.create()
                    .get().uri(base + "/api/method/frappe.auth.get_logged_user")
                    .header("Authorization", "token " + apiKey + ":" + apiSecret)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(Map.class);

            // Success shape: {"message": "user@example.com"}. Anything without a resolved user = failure.
            Object user = response != null ? response.get("message") : null;
            if (user != null && !String.valueOf(user).isBlank()) {
                persistTestResult(true, String.valueOf(user), null);
                return ResponseEntity.ok(Map.of("status", "success", "uid", String.valueOf(user)));
            }
            persistTestResult(false, null, "Authentication failed — wrong URL or API key/secret.");
            return ResponseEntity.badRequest().body(Map.of("error", "Authentication failed — wrong URL or API key/secret."));

        } catch (Exception e) {
            log.warn("ERPNext Test Connection failed: {}", e.getMessage());
            persistTestResult(false, null, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Pull a human message out of an Odoo JSON-RPC error envelope. */
    @SuppressWarnings("unchecked")
    private String extractOdooError(Object envelope) {
        try {
            if (envelope instanceof Map<?, ?> m) {
                Object data = m.get("data");
                if (data instanceof Map<?, ?> dm && dm.get("message") != null) return String.valueOf(dm.get("message"));
                if (m.get("message") != null) return String.valueOf(m.get("message"));
            }
        } catch (Exception ignored) { /* fall through */ }
        return "unauthorized or unreachable";
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

    private String getServiceToken() {
        try {
            return restClientBuilder.build()
                .post()
                .uri("http://keycloak:8080/realms/master/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&client_id=app-backend&client_secret=AppBackendSecret2026!")
                .retrieve()
                .body(java.util.Map.class)
                .get("access_token").toString();
        } catch (Exception e) {
            log.warn("Failed to get service token for delivery push: {}", e.getMessage());
            return "";
        }
    }
}
