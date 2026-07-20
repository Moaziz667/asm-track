package com.asm.delivery.erp;

import com.asm.delivery.security.TenantContext;
import com.asm.delivery.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
        String provider = settings.get(SETTING_KEY);
        if (provider == null || provider.isBlank()) {
            log.debug("No erp.provider setting for tenant {} — falling back to: {}", companyId, FALLBACK);
            return FALLBACK;
        }
        return provider.trim().toLowerCase();
    }
}
