package com.asm.appbackend.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter (in-memory, single-node).
 * For multi-node deployments replace with Redis-backed counters.
 */
@Service
public class RateLimitService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_MS   = 60_000;

    private final ConcurrentHashMap<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    public boolean isAllowed(String key) {
        final long now = System.currentTimeMillis();

        buckets.compute(key, (k, deque) -> {
            if (deque == null) deque = new ArrayDeque<>();
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            deque.addLast(now);
            return deque;
        });

        return buckets.get(key).size() <= MAX_ATTEMPTS;
    }

    /** Evict keys whose last attempt is older than the window — prevents unbounded growth. */
    @Scheduled(fixedDelay = 120_000)
    public void evictStaleKeys() {
        final long cutoff = System.currentTimeMillis() - WINDOW_MS;
        buckets.entrySet().removeIf(entry -> {
            Deque<Long> deque = entry.getValue();
            return deque.isEmpty() || deque.peekLast() < cutoff;
        });
    }
}
