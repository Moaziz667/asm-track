package com.asm.erpadapter.routing;

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
    private final SettingsClient settingsClient;

    /**
     * Collects all ERP port implementations.
     * Naming convention: sync="odoo", lookup="odooLookup", order="odooOrder".
     */
    public ErpProviderRouter(List<ErpSyncPort> syncBeans,
                              List<ErpLookupPort> lookupBeans,
                              List<ErpOrderPort> orderBeans,
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
        log.info("ERP providers registered — sync: {}, lookup: {}, order: {}",
                syncAdapters.keySet(), lookupAdapters.keySet(), orderAdapters.keySet());
    }

    public ErpSyncPort getSync(String providerRequested) {
        String provider = resolveProvider();
        ErpSyncPort adapter = syncAdapters.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown ERP sync provider: " + provider
                    + ". Available: " + syncAdapters.keySet());
        }
        return adapter;
    }

    public ErpLookupPort getLookup(String providerRequested) {
        String provider = resolveProvider();
        ErpLookupPort adapter = lookupAdapters.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown ERP lookup provider: " + provider
                    + ". Available: " + lookupAdapters.keySet());
        }
        return adapter;
    }

    public ErpOrderPort getOrder(String providerRequested) {
        String provider = resolveProvider();
        ErpOrderPort adapter = orderAdapters.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown ERP order provider: " + provider
                    + ". Available: " + orderAdapters.keySet());
        }
        return adapter;
    }

    /**
     * Extracts the provider key from the adapter class name.
     * OdooSyncAdapter → "odoo", OdooLookupAdapter → "odoo", DuxSyncAdapter → "dux".
     */
    private static String extractProviderKey(Class<?> clazz) {
        String name = clazz.getSimpleName(); // e.g. "OdooSyncAdapter"
        // Remove "Sync/Lookup/Order/Adapter" suffixes to get the provider prefix
        String cleaned = name.replaceAll("(Sync|Lookup|Order|Adapter)", "");
        return cleaned.toLowerCase(); // "odoo", "dux", etc.
    }

    private String resolveProvider() {
        String active = settingsClient.getSettings().getActiveErpProvider();
        if (active == null || active.isBlank() || active.equalsIgnoreCase("NONE")) {
            return "none";
        }
        return active.trim().toLowerCase();
    }
}
