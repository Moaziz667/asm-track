package com.asm.erpadapter.service;

import com.asm.erpadapter.client.SettingsInternalClient;
import com.asm.erpadapter.dto.SystemSettingsDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches ERP connection settings from AppBackend, with a 60-second cache and a safe fallback.
 * The HTTP call and its service-token auth are delegated to {@link SettingsInternalClient} (Feign);
 * this class only owns the cache and fallback policy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsClient {

    private final SettingsInternalClient settingsInternalClient;

    // Simple cache: 60 seconds
    private final AtomicReference<SystemSettingsDto> cachedSettings = new AtomicReference<>();
    private final AtomicLong cacheTimestamp = new AtomicLong(0);

    private static final long CACHE_TTL_MS = 30_000;
    private static final SystemSettingsDto NONE = SystemSettingsDto.builder().activeErpProvider("NONE").build();

    public SystemSettingsDto getSettings() {
        long now = System.currentTimeMillis();
        if (cachedSettings.get() != null && (now - cacheTimestamp.get()) < CACHE_TTL_MS) {
            return gate(cachedSettings.get());
        }
        try {
            SystemSettingsDto settings = settingsInternalClient.getErpSettings();
            if (settings != null) {
                cachedSettings.set(settings);
                cacheTimestamp.set(now);
                return gate(settings);
            }
        } catch (Exception e) {
            log.error("Failed to fetch ERP settings from AppBackend: {}", e.getMessage());
        }
        // Fetch failed: serve last-known-good ONLY if it was a healthy (CONNECTED) connection.
        // We must never keep pulling orders with credentials that have since been marked ERROR.
        return cachedSettings.get() != null ? gate(cachedSettings.get()) : NONE;
    }

    /**
     * Refuse to expose credentials whose connection isn't verified CONNECTED. A saved-but-untested
     * (CONFIGURED) or failed (ERROR) connection returns NONE, so callers stop pulling orders instead
     * of silently using stale-but-cached good creds. Back-compat: a null status (older AppBackend
     * that doesn't send it yet) is treated as usable so we don't break existing deployments.
     */
    private SystemSettingsDto gate(SystemSettingsDto s) {
        if (s == null) return NONE;
        String status = s.getConnectionStatus();
        if (status == null || "CONNECTED".equalsIgnoreCase(status)) return s;
        log.warn("ERP connection status is '{}' (not CONNECTED) — refusing to use these credentials.", status);
        return NONE;
    }
}
