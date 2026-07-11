package com.asm.delivery.service.analytics;

import com.asm.delivery.entity.Zone;
import com.asm.delivery.repository.ZoneRepository;
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
 */
@Component
@RequiredArgsConstructor
public class ZoneResolver {

    private final ZoneRepository zoneRepository;
    private final Map<String, UUID> cache = new ConcurrentHashMap<>();

    /**
     * Resolve a zone name to its id; null/blank or unknown name yields null (no filter).
     * Results are cached for the lifetime of the application context.
     */
    public UUID resolve(String zoneName) {
        if (zoneName == null || zoneName.isBlank()) return null;
        String key = zoneName.trim().toLowerCase();
        return cache.computeIfAbsent(key, k ->
                zoneRepository.findAll().stream()
                        .filter(z -> k.equalsIgnoreCase(z.getName()))
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
