package com.asm.delivery.service.analytics;

import com.asm.delivery.entity.Zone;
import com.asm.delivery.repository.ZoneRepository;
import com.asm.delivery.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared zone-name-to-id resolver with in-memory cache.
 * Replaces the duplicate {@code resolveZoneId} methods in OpsAnalyticsService,
 * DriverPerformanceService, and ReportingService.
 *
 * <p><b>Multi-tenant:</b> zones live in each tenant's schema, so two tenants can each have a zone
 * named "Nord" with different ids. The cache is therefore keyed by {@code companyId} + name — a
 * name-only key would return tenant A's zone id to tenant B, silently corrupting B's analytics filter.
 */
@Component
@RequiredArgsConstructor
public class ZoneResolver {

    private final ZoneRepository zoneRepository;
    // Key = "<companyId>::<zoneName>" so a zone name never resolves to another tenant's zone id.
    private final Map<String, UUID> cache = new ConcurrentHashMap<>();

    /**
     * Resolve a zone name to its id; null/blank or unknown name yields null (no filter).
     * Results are cached per tenant for the lifetime of the application context.
     */
    public UUID resolve(String zoneName) {
        if (zoneName == null || zoneName.isBlank()) return null;
        String name = zoneName.trim().toLowerCase();
        UUID companyId = TenantContext.get();
        String cacheKey = (companyId != null ? companyId.toString() : "__no_tenant__") + "::" + name;
        return cache.computeIfAbsent(cacheKey, k ->
                zoneRepository.findAll().stream()
                        .filter(z -> name.equalsIgnoreCase(z.getName()))
                        .map(Zone::getId)
                        .findFirst()
                        .orElse(null)
        );
    }

    /** Resolve a list of zone names to ids; blanks/unknowns dropped, deduped. Empty when none resolve. */
    public List<UUID> resolveAll(List<String> zoneNames) {
        if (zoneNames == null || zoneNames.isEmpty()) return List.of();
        return zoneNames.stream()
                .map(this::resolve)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
