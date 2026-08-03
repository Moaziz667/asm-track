package com.asm.erpadapter.adapter.odoo;

import com.asm.erpadapter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects and caches the Odoo major version of each tenant's instance. The detected version drives the
 * {@link CapabilityRegistry} field/method resolution, so the single adapter can speak the right dialect per
 * tenant instead of assuming one version.
 *
 * <p>Read via {@code ir.module.module} base.latest_version (works on every version). Cached per tenant
 * ({@link TenantContext}) with a long TTL — an ERP instance almost never changes major version at runtime,
 * and a re-detect after {@link #invalidate()} (e.g. on a credential/URL change) picks up a migration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OdooVersionResolver {

    private final OdooJsonRpcClient rpc;

    private record Cached(String version, int major, long atMs) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 30 * 60 * 1000L; // 30 min
    private static final String NO_TENANT = "__no_tenant__";

    /** Raw version string, e.g. "17.0.1.4" (or "unknown"). */
    public String version() {
        return resolve().version();
    }

    /** Leading major, e.g. 17 (0 when unknown). */
    public int major() {
        return resolve().major();
    }

    /** Drop the cached version for the current tenant so the next call re-detects (post credential change). */
    public void invalidate() {
        cache.remove(tenantKey());
    }

    private Cached resolve() {
        String key = tenantKey();
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && (now - c.atMs()) < TTL_MS) return c;
        String v = detect();
        Cached fresh = new Cached(v, parseMajor(v), now);
        cache.put(key, fresh);
        if (!"unknown".equals(v)) log.info("Odoo version detected for tenant {} → {}", key, v);
        return fresh;
    }

    private String tenantKey() {
        UUID companyId = TenantContext.get();
        return companyId != null ? companyId.toString() : NO_TENANT;
    }

    /** Read base.latest_version from ir.module.module. Null-safe → "unknown" on any failure. */
    @SuppressWarnings("unchecked")
    private String detect() {
        try {
            Map<String, Object> resp = rpc.callRpc(rpc.buildArgs("ir.module.module", "search_read",
                    List.of(List.of(List.of("name", "=", "base"))),
                    Map.of("fields", List.of("latest_version"), "limit", 1)));
            if (resp == null || resp.containsKey("error")) return "unknown";
            Object result = resp.get("result");
            if (result instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> row) {
                Object v = ((Map<String, Object>) row).get("latest_version");
                return v != null ? String.valueOf(v) : "unknown";
            }
            return "unknown";
        } catch (Exception e) {
            log.debug("Odoo version detection failed: {}", e.getMessage());
            return "unknown";
        }
    }

    /** "17.0.1.4" → 17; 0 when unknown/unparseable. */
    static int parseMajor(String version) {
        if (version == null || version.isBlank()) return 0;
        try {
            int dot = version.indexOf('.');
            return Integer.parseInt(dot > 0 ? version.substring(0, dot) : version);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
