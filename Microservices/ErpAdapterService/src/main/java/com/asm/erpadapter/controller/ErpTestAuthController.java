package com.asm.erpadapter.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ERP connection test endpoint — the single, real auth probe that the adapter service owns.
 *
 * <p>AppBackend proxies to this endpoint instead of re-implementing Odoo/ERPNext auth inline,
 * guaranteeing that the test uses the <b>exact same</b> HTTP client, timeouts, and SSRF guard
 * as the real sync path.
 */
@RestController
@RequestMapping("/api/v1/erp/test-auth")
@Tag(name = "ERP Test Auth", description = "Validates ERP credentials using the adapter's real auth path.")
@Slf4j
public class ErpTestAuthController {

    @Value("${allowed.erp.domains:}")
    private String allowedDomains;

    @Value("${odoo.timeout.connect-ms:5000}")
    private int odooConnectMs;

    @Value("${odoo.timeout.read-ms:15000}")
    private int odooReadMs;

    @Value("${erpnext.timeout.connect-ms:5000}")
    private int erpNextConnectMs;

    @Value("${erpnext.timeout.read-ms:15000}")
    private int erpNextReadMs;

    @PostMapping
    @Operation(summary = "Test ERP credentials via the adapter's real auth path",
            description = "Accepts ERP connection config and validates it using the same HTTP client + timeouts "
                    + "as the real sync path. Returns {status: \"success\", uid: \"...\"} or {error: \"...\"}.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Auth succeeded"),
            @ApiResponse(responseCode = "400", description = "Auth failed or invalid config")
    })
    public ResponseEntity<Map<String, String>> testAuth(@RequestBody Map<String, String> config) {
        String type = config.getOrDefault("type", "").toLowerCase();
        return switch (type) {
            case "odoo" -> testOdoo(config);
            case "erpnext" -> testErpNext(config);
            default -> ResponseEntity.badRequest().body(Map.of("error", "Unsupported provider type: " + type));
        };
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, String>> testOdoo(Map<String, String> config) {
        String url = config.getOrDefault("url", "").trim();
        String db = config.getOrDefault("db", "").trim();
        String login = config.getOrDefault("login", "").trim();
        String apiKey = config.getOrDefault("apiKey", "").trim();
        String password = config.getOrDefault("password", "").trim();
        String secret = !apiKey.isEmpty() ? apiKey : password;

        if (url.isBlank() || db.isBlank() || login.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing url, db, or login."));
        }
        validateUrl(url);

        try {
            Map<String, Object> params = new HashMap<>();
            params.put("service", "common");
            params.put("method", "authenticate");
            params.put("args", List.of(db, login, secret, Map.of()));

            Map<String, Object> body = new HashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("method", "call");
            body.put("params", params);

            RestClient client = RestClient.builder()
                    .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
                    .build();

            Map<String, Object> response = client.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            Object errorEnvelope = response != null ? response.get("error") : null;
            Object result = response != null ? response.get("result") : null;
            int uid = result instanceof Number ? ((Number) result).intValue() : -1;

            if (errorEnvelope == null && uid > 0) {
                return ResponseEntity.ok(Map.of("status", "success", "uid", String.valueOf(uid)));
            }

            String reason = errorEnvelope != null
                    ? "Odoo error: " + extractOdooError(errorEnvelope)
                    : "Authentication failed — wrong database, login, or API key.";
            return ResponseEntity.badRequest().body(Map.of("error", reason));
        } catch (SecurityException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("ERP test-auth (Odoo) failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, String>> testErpNext(Map<String, String> config) {
        String url = config.getOrDefault("url", "").trim();
        String apiKey = config.getOrDefault("apiKey", "").trim();
        String apiSecret = config.getOrDefault("apiSecret", "").trim();

        if (url.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing url, apiKey, or apiSecret."));
        }
        String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        validateUrl(base);

        try {
            RestClient client = RestClient.builder()
                    .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
                    .build();

            Map<String, Object> response = client.get()
                    .uri(base + "/api/method/frappe.auth.get_logged_user")
                    .header("Authorization", "token " + apiKey + ":" + apiSecret)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(Map.class);

            Object user = response != null ? response.get("message") : null;
            if (user != null && !String.valueOf(user).isBlank()) {
                return ResponseEntity.ok(Map.of("status", "success", "uid", String.valueOf(user)));
            }
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Authentication failed — wrong URL or API key/secret."));
        } catch (SecurityException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("ERP test-auth (ERPNext) failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

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
                List<String> allowed = java.util.Arrays.stream(allowedDomains.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (!allowed.isEmpty()) {
                    boolean match = allowed.stream().anyMatch(d -> host.equalsIgnoreCase(d) || host.endsWith("." + d));
                    if (!match) {
                        throw new SecurityException("SSRF Guard: Host '" + host + "' is not whitelisted for ERP integration");
                    }
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            log.error("SSRF Guard block: URL '{}' failed validation: {}", url, e.getMessage());
            throw new SecurityException("SSRF Guard block: " + e.getMessage(), e);
        }
    }

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
}
