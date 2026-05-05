package com.asm.appbackend.security;

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
    private static final long WINDOW_MS   = 60_000; // 1 minute

    private final ConcurrentHashMap<String, Deque<Long>> buckets = new ConcurrentHashMap<>();

    /**
     * Returns true if the request is allowed; false if the rate limit is exceeded.
     * Records the current attempt regardless.
     */
    public boolean isAllowed(String key) {
        final long now = System.currentTimeMillis();

        buckets.compute(key, (k, deque) -> {
            if (deque == null) deque = new ArrayDeque<>();
            // evict timestamps older than the window
            while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
                deque.pollFirst();
            }
            deque.addLast(now);
            return deque;
        });

        return buckets.get(key).size() <= MAX_ATTEMPTS;
    }
}
