package com.asm.erpadapter.routing;

import com.asm.erpadapter.conformance.ErpConformanceProbe;
import com.asm.erpadapter.port.ErpChangePort;
import com.asm.erpadapter.port.ErpLookupPort;
import com.asm.erpadapter.port.ErpOrderPort;
import com.asm.erpadapter.port.ErpSyncPort;
import com.asm.erpadapter.service.SettingsClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes requests to the correct ERP adapter based on the provider name.
 *
 * Each adapter registers itself with a bean name prefix (e.g. "odoo", "odooLookup").
 * The router normalizes these into a clean provider key → implementation lookup.
 * To add a new ERP: create beans named "dux", "duxLookup", "duxOrder".
 */
@Component
@Slf4j
public class ErpProviderRouter {

    private final Map<String, ErpSyncPort> syncAdapters = new HashMap<>();
    private final Map<String, ErpLookupPort> lookupAdapters = new HashMap<>();
    private final Map<String, ErpOrderPort> orderAdapters = new HashMap<>();
    private final Map<String, ErpChangePort> changeAdapters = new HashMap<>();
    private final Map<String, ErpConformanceProbe> conformanceProbes = new HashMap<>();
    private final SettingsClient settingsClient;

    /**
     * Collects all ERP port implementations.
     * Naming convention: sync="odoo", lookup="odooLookup", order="odooOrder", change="odooChange".
     */
    public ErpProviderRouter(List<ErpSyncPort> syncBeans,
                              List<ErpLookupPort> lookupBeans,
                              List<ErpOrderPort> orderBeans,
                              List<ErpChangePort> changeBeans,
                              List<ErpConformanceProbe> probeBeans,
                              SettingsClient settingsClient) {
        this.settingsClient = settingsClient;
        for (ErpSyncPort bean : syncBeans) {
            String key = extractProviderKey(bean.getClass());
            syncAdapters.put(key, bean);
        }
        for (ErpLookupPort bean : lookupBeans) {
            String key = extractProviderKey(bean.getClass());
            lookupAdapters.put(key, bean);
        }
        for (ErpOrderPort bean : orderBeans) {
            String key = extractProviderKey(bean.getClass());
            orderAdapters.put(key, bean);
        }
        for (ErpChangePort bean : changeBeans) {
            String key = extractProviderKey(bean.getClass());
            changeAdapters.put(key, bean);
        }
        // Conformance probes self-declare their provider key (no class-name convention).
        for (ErpConformanceProbe bean : probeBeans) {
            conformanceProbes.put(bean.provider().toLowerCase(), bean);
        }
        log.info("ERP providers registered — sync: {}, lookup: {}, order: {}, change: {}, probe: {}",
                syncAdapters.keySet(), lookupAdapters.keySet(), orderAdapters.keySet(),
                changeAdapters.keySet(), conformanceProbes.keySet());
    }

    // The provider is resolved from the CURRENT TENANT's settings (via SettingsClient + the propagated
    // X-Company-Id), never from the caller — a tenant on ERPNext must never be served the Odoo adapter
    // just because a caller passed "odoo". So these take no provider argument by design.

    public ErpSyncPort getSync() {
        String provider = resolveProvider();
        ErpSyncPort adapter = syncAdapters.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown ERP sync provider: " + provider
                    + ". Available: " + syncAdapters.keySet());
        }
        return adapter;
    }

    public ErpLookupPort getLookup() {
        String provider = resolveProvider();
        ErpLookupPort adapter = lookupAdapters.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown ERP lookup provider: " + provider
                    + ". Available: " + lookupAdapters.keySet());
        }
        return adapter;
    }

    public ErpOrderPort getOrder() {
        String provider = resolveProvider();
        ErpOrderPort adapter = orderAdapters.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown ERP order provider: " + provider
                    + ". Available: " + orderAdapters.keySet());
        }
        return adapter;
    }

    /**
     * The conformance probe ("drytest") for the current tenant's ERP family, or empty when the tenant
     * has no ERP configured ({@code none}). Read-only certification — safe against production.
     */
    public java.util.Optional<ErpConformanceProbe> getProbe() {
        String provider = resolveProvider();
        return java.util.Optional.ofNullable(conformanceProbes.get(provider));
    }

    /**
     * The inbound-change adapter for the current tenant's ERP, or empty when the tenant has no ERP
     * configured ({@code none}) — so the inbound poller can cleanly skip un-configured tenants without
     * treating "no ERP" as an error.
     */
    public java.util.Optional<ErpChangePort> getChange() {
        String provider = resolveProvider();
        return java.util.Optional.ofNullable(changeAdapters.get(provider));
    }

    /**
     * Extracts the provider key from the adapter class name.
     * OdooSyncAdapter → "odoo", OdooLookupAdapter → "odoo", DuxSyncAdapter → "dux".
     */
    private static String extractProviderKey(Class<?> clazz) {
        String name = clazz.getSimpleName(); // e.g. "OdooSyncAdapter"
        // Remove "Sync/Lookup/Order/Change/Adapter" suffixes to get the provider prefix
        String cleaned = name.replaceAll("(Sync|Lookup|Order|Change|Adapter)", "");
        return cleaned.toLowerCase(); // "odoo", "erpnext", "dux", etc.
    }

    /**
     * The current tenant's ERP family, or {@code "none"} when it has not configured one.
     *
     * <p>Exposed so callers that route on something other than a port — the field catalogue and the
     * mapping resolver, which are per-provider but not adapters — can select their own
     * implementation without a second copy of this lookup.
     */
    public String provider() {
        return resolveProvider();
    }

    private String resolveProvider() {
        String active = settingsClient.getSettings().getActiveErpProvider();
        if (active == null || active.isBlank() || active.equalsIgnoreCase("NONE")) {
            return "none";
        }
        return active.trim().toLowerCase();
    }
}
