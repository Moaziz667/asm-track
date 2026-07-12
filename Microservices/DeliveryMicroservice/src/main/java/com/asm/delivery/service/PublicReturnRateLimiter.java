package com.asm.delivery.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory sliding-window rate limiter for the public (unauthenticated) return endpoints.
 * Keyed by {@code ip:deliveryId} so a single client can raise at most {@value #MAX_REQUESTS} return
 * attempts per {@link #WINDOW} against one delivery — enough to retry a failed submit, not enough to
 * abuse the open endpoint.
 *
 * <p>Single-instance service, so a JVM-local map is sufficient. Empty keys are pruned so the map does
 * not grow unbounded across distinct {@code ip:deliveryId} pairs.
 */
@Component
public class PublicReturnRateLimiter {

    private static final int MAX_REQUESTS = 4;
    private static final Duration WINDOW = Duration.ofHours(24);

    private final ConcurrentHashMap<String, List<Instant>> hits = new ConcurrentHashMap<>();

    /** @return true if the call is allowed (and recorded), false if the window budget is exhausted. */
    public boolean tryAcquire(String ip, UUID deliveryId) {
        String key = (ip == null ? "unknown" : ip) + ":" + deliveryId;
        Instant cutoff = Instant.now().minus(WINDOW);

        List<Instant> timestamps = hits.compute(key, (k, existing) -> {
            List<Instant> list = existing == null ? new CopyOnWriteArrayList<>() : existing;
            list.removeIf(t -> t.isBefore(cutoff));
            return list;
        });

        if (timestamps.size() >= MAX_REQUESTS) {
            return false;
        }
        timestamps.add(Instant.now());
        return true;
    }

    /** Drop keys whose window has fully drained, so the map cannot leak. Cheap; call opportunistically. */
    public void evictEmpty() {
        Instant cutoff = Instant.now().minus(WINDOW);
        hits.forEach((k, list) -> {
            list.removeIf(t -> t.isBefore(cutoff));
            if (list.isEmpty()) hits.remove(k, list);
        });
    }
}
