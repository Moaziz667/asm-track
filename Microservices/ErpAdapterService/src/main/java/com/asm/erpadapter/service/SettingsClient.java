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

    public SystemSettingsDto getSettings() {
        long now = System.currentTimeMillis();
        if (cachedSettings.get() != null && (now - cacheTimestamp.get()) < 60_000) {
            return cachedSettings.get();
        }
        try {
            SystemSettingsDto settings = settingsInternalClient.getErpSettings();
            if (settings != null) {
                cachedSettings.set(settings);
                cacheTimestamp.set(now);
                return settings;
            }
        } catch (Exception e) {
            log.error("Failed to fetch ERP settings from AppBackend: {}", e.getMessage());
        }
        // Return last known good settings if available, else a safe default
        return cachedSettings.get() != null ? cachedSettings.get() : new SystemSettingsDto("NONE", null);
    }
}
