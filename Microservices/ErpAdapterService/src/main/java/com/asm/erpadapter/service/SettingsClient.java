package com.asm.erpadapter.service;

import com.asm.erpadapter.client.SettingsInternalClient;
import com.asm.erpadapter.dto.SystemSettingsDto;
import com.asm.erpadapter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches ERP connection settings from AppBackend, with a short cache and a safe fallback.
 * The HTTP call and its service-token auth are delegated to {@link SettingsInternalClient} (Feign);
 * this class only owns the cache and fallback policy.
 *
 * <p><b>Multi-tenant:</b> each tenant has its own ERP provider + credentials (AppBackend stores them
 * per schema). The cache is therefore keyed by {@code companyId} ({@link TenantContext}); a single
 * shared entry would let the first tenant's config be served to every other tenant for the TTL window
 * — e.g. an Odoo tenant's BLs showing up on an ERPNext tenant's import page. The Feign call itself
 * forwards {@code X-Company-Id} (see {@code ServiceClientConfig}), so AppBackend returns the right
 * schema's row; the per-tenant key just stops the cache from crossing the wires back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettingsClient {

    private final SettingsInternalClient settingsInternalClient;

    /** One cache entry per tenant: the last-fetched settings and when we fetched them. */
    private record Entry(SystemSettingsDto settings, long fetchedAtMs) {}

    // Keyed by companyId (or NO_TENANT when there is no tenant context) so tenants never share config.
    private final Map<String, Entry> cacheByTenant = new ConcurrentHashMap<>();
    private static final String NO_TENANT = "__no_tenant__";

    private static final long CACHE_TTL_MS = 30_000;
    private static final SystemSettingsDto NONE = SystemSettingsDto.builder().activeErpProvider("NONE").build();

    public SystemSettingsDto getSettings() {
        String tenantKey = tenantKey();
        long now = System.currentTimeMillis();
        Entry cached = cacheByTenant.get(tenantKey);
        if (cached != null && (now - cached.fetchedAtMs()) < CACHE_TTL_MS) {
            return gate(cached.settings());
        }
        try {
            SystemSettingsDto settings = settingsInternalClient.getErpSettings();
            if (settings != null) {
                cacheByTenant.put(tenantKey, new Entry(settings, now));
                return gate(settings);
            }
        } catch (Exception e) {
            log.error("Failed to fetch ERP settings from AppBackend for tenant {}: {}", tenantKey, e.getMessage());
        }
        // Fetch failed: serve this tenant's last-known-good ONLY if it was a healthy (CONNECTED)
        // connection. We must never keep pulling orders with credentials since marked ERROR.
        return cached != null ? gate(cached.settings()) : NONE;
    }

    /** Cache key for the current tenant — the company id, or a stable sentinel when none is set. */
    private String tenantKey() {
        UUID companyId = TenantContext.get();
        return companyId != null ? companyId.toString() : NO_TENANT;
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
