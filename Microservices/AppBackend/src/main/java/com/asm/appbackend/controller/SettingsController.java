package com.asm.appbackend.controller;

import com.asm.appbackend.dto.SystemSettingsDto;
import com.asm.appbackend.entity.SystemSettings;
import com.asm.appbackend.repository.SystemSettingsRepository;
import com.asm.appbackend.service.EncryptionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final SystemSettingsRepository repository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${allowed.erp.domains:}")
    private String allowedDomains;

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
    @PreAuthorize("hasRole('ADMIN')")
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
            } catch (JsonProcessingException e) {
                log.error("Failed to parse decrypted ERP config", e);
            }
        }

        return ResponseEntity.ok(new SystemSettingsDto(settings.getActiveErpProvider(), configMap));
    }

    /**
     * Used by the React Admin Dashboard to update the ERP settings.
     * The JSON payload is encrypted before saving.
     */
    @PutMapping("/erp")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> updateErpSettings(@RequestBody SystemSettingsDto dto) {
        SystemSettings settings = repository.findById("SINGLETON").orElse(new SystemSettings());
        settings.setId("SINGLETON");
        settings.setActiveErpProvider(dto.getActiveErpProvider());

        if (dto.getErpConfiguration() != null) {
            try {
                // If the frontend sends masked passwords, we need to ignore them and keep the old password
                Map<String, Object> newConfig = objectMapper.convertValue(dto.getErpConfiguration(), new TypeReference<Map<String, Object>>() {});
                
                if (settings.getErpConfiguration() != null) {
                    String decryptedOldJson = encryptionService.decrypt(settings.getErpConfiguration());
                    Map<String, Object> oldConfig = objectMapper.readValue(decryptedOldJson, new TypeReference<Map<String, Object>>() {});
                    
                    // Restore original password if frontend sent mask
                    if ("********".equals(newConfig.get("password")) && oldConfig.containsKey("password")) {
                        newConfig.put("password", oldConfig.get("password"));
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

        repository.save(settings);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/erp/test")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> testErpSettings(@RequestBody SystemSettingsDto dto) {
        if ("DUX".equals(dto.getActiveErpProvider())) {
            // Placeholder for next week's DUX adapter implementation
            return ResponseEntity.ok(Map.of("status", "success", "message", "DUX adapter testing will be implemented next week."));
        }

        if (!"ODOO".equals(dto.getActiveErpProvider()) || dto.getErpConfiguration() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unsupported or missing provider config"));
        }

        try {
            Map<String, Object> config = objectMapper.convertValue(dto.getErpConfiguration(), new TypeReference<>() {});
            
            // If password is masked, we must retrieve the real one from DB to test it
            if ("********".equals(config.get("password"))) {
                SystemSettings settings = repository.findById("SINGLETON").orElse(null);
                if (settings != null && settings.getErpConfiguration() != null) {
                    String decryptedOldJson = encryptionService.decrypt(settings.getErpConfiguration());
                    Map<String, Object> oldConfig = objectMapper.readValue(decryptedOldJson, new TypeReference<>() {});
                    if (oldConfig.containsKey("password")) {
                        config.put("password", oldConfig.get("password"));
                    }
                }
            }

            String url = String.valueOf(config.get("url"));
            validateUrl(url);
            String db = String.valueOf(config.get("db"));
            Object rawUid = config.get("uid");
            int uid = rawUid instanceof Number ? ((Number) rawUid).intValue() : Integer.parseInt(String.valueOf(rawUid));
            String password = String.valueOf(config.get("password"));

            Map<String, Object> params = new HashMap<>();
            params.put("service", "object");
            params.put("method", "execute_kw");
            params.put("args", List.of(db, uid, password, "res.users", "read", List.of(List.of(uid))));

            Map<String, Object> body = new HashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("method", "call");
            body.put("params", params);

            RestTemplate restTemplate = new RestTemplate();
            Map response = restTemplate.postForObject(url, body, Map.class);
            
            if (response != null && response.containsKey("result")) {
                return ResponseEntity.ok(Map.of("status", "success"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Connection failed or unauthorized"));
            }

        } catch (Exception e) {
            log.warn("ERP Test Connection failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * INTERNAL ENDPOINT: Used by ErpAdapterService to fetch the unmasked, decrypted credentials.
     * This endpoint must be secured via internal network rules or machine-to-machine OAuth tokens.
     */
    @GetMapping("/internal/erp")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<SystemSettingsDto> getInternalErpSettings() {
        SystemSettings settings = repository.findById("SINGLETON").orElse(null);
        if (settings == null) {
            return ResponseEntity.notFound().build();
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

        return ResponseEntity.ok(new SystemSettingsDto(settings.getActiveErpProvider(), configMap));
    }
}
