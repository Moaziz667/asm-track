package com.asm.delivery.erp;

import com.asm.tenant.TenantContext;
import com.asm.delivery.service.SystemSettingsService;
import com.asm.delivery.transport.adapters.AppBackendSettingsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the active ERP provider for the current tenant by reading
 * {@code erp.provider} from the tenant's {@code system_settings} key-value table.
 *
 * <p>Falls back to {@code "odoo"} (the historical default) when the key is absent,
 * so tenants that were never configured via the admin UI keep working.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantErpProviderResolver {

    private static final String SETTING_KEY = "erp.provider";
    private static final String FALLBACK = "odoo";

    private final SystemSettingsService settings;
    private final AppBackendSettingsClient appBackendSettingsClient;

    /** Per-tenant cache: provider name + when it was fetched. */
    private record Entry(String provider, long fetchedAtMs) {}

    private final Map<String, Entry> cacheByTenant = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30_000;

    /**
     * @return the lowercase ERP provider key (e.g. "odoo", "erpnext") for the current tenant,
     *         or {@code "odoo"} if no tenant context or no setting stored.
     */
    public String resolve() {
        UUID companyId = TenantContext.get();
        if (companyId == null) {
            log.debug("No tenant context — falling back to default ERP provider: {}", FALLBACK);
            return FALLBACK;
        }

        String tenantKey = companyId.toString();
        long now = System.currentTimeMillis();

        // 1. Check in-memory cache (avoids repeated Feign calls within TTL window)
        Entry cached = cacheByTenant.get(tenantKey);
        if (cached != null && (now - cached.fetchedAtMs()) < CACHE_TTL_MS) {
            return cached.provider();
        }

        // 2. Fetch from AppBackend (single source of truth) via lightweight Feign call
        try {
            Map<String, String> response = appBackendSettingsClient.getErpProvider();
            String provider = response != null ? response.get("provider") : null;
            if (provider != null && !provider.isBlank() && !"none".equalsIgnoreCase(provider)) {
                String resolved = provider.trim().toLowerCase();
                cacheByTenant.put(tenantKey, new Entry(resolved, now));
                return resolved;
            }
        } catch (Exception e) {
            log.warn("Failed to fetch ERP provider from AppBackend for tenant {}: {}", tenantKey, e.getMessage());
        }

        // 3. Fallback: serve last-known-good from cache if available
        if (cached != null) {
            return cached.provider();
        }

        // 4. Final fallback: local system_settings (may be stale from push) or default "odoo"
        String localProvider = settings.get(SETTING_KEY);
        if (localProvider != null && !localProvider.isBlank()) {
            String resolved = localProvider.trim().toLowerCase();
            cacheByTenant.put(tenantKey, new Entry(resolved, now));
            return resolved;
        }

        log.debug("No erp.provider setting for tenant {} — falling back to: {}", companyId, FALLBACK);
        return FALLBACK;
    }
}
