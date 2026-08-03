package com.asm.delivery.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sliding-window rate limiting, keyed by {@code ip:deliveryId}.
 *
 * <p>The mechanics live here so each public endpoint can carry its own budget without carrying its
 * own copy of the algorithm. The budget is the interesting part and it differs sharply by endpoint:
 * submitting a return is a once-in-a-delivery act, while watching a delivery is a poll. A single
 * shared limit would either break the tracking page or leave the return endpoint wide open.
 *
 * <p>In-memory, so the budget is per instance. That is honest for a single-instance deployment and
 * would need Redis behind a load balancer — noted rather than pretended otherwise.
 */
public abstract class SlidingWindowRateLimiter {

    private final int maxRequests;
    private final Duration window;
    private final ConcurrentHashMap<String, List<Instant>> hits = new ConcurrentHashMap<>();

    protected SlidingWindowRateLimiter(int maxRequests, Duration window) {
        this.maxRequests = maxRequests;
        this.window = window;
    }

    /** @return true if the call is allowed (and recorded), false if the window budget is exhausted. */
    public boolean tryAcquire(String ip, UUID deliveryId) {
        String key = (ip == null ? "unknown" : ip) + ":" + deliveryId;
        Instant cutoff = Instant.now().minus(window);

        List<Instant> timestamps = hits.compute(key, (k, existing) -> {
            List<Instant> list = existing == null ? new CopyOnWriteArrayList<>() : existing;
            list.removeIf(t -> t.isBefore(cutoff));
            return list;
        });

        if (timestamps.size() >= maxRequests) {
            return false;
        }
        timestamps.add(Instant.now());
        return true;
    }

    /** Drop keys whose window has fully drained, so the map cannot leak. Cheap; call opportunistically. */
    public void evictEmpty() {
        Instant cutoff = Instant.now().minus(window);
        hits.forEach((k, list) -> {
            list.removeIf(t -> t.isBefore(cutoff));
            if (list.isEmpty()) hits.remove(k, list);
        });
    }
}
