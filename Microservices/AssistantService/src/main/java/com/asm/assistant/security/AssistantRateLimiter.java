package com.asm.assistant.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-principal sliding-window rate limiter (same shape as the platform's {@code SlidingWindowRateLimiter}),
 * kept in-process — no Redis, no new infra. Answering is LLM-backed and therefore costly and abusable,
 * so each (tenant + user) key gets a bounded number of requests per window; excess is rejected before
 * any retrieval or model call happens.
 */
@Component
public class AssistantRateLimiter {

    private final int maxRequests;
    private final long windowMs;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public AssistantRateLimiter(
            @Value("${assistant.ratelimit.max-requests:30}") int maxRequests,
            @Value("${assistant.ratelimit.window-seconds:60}") long windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowMs = Duration.ofSeconds(windowSeconds).toMillis();
    }

    /** @return true if the request is allowed; false if the key exceeded its window budget. */
    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
                q.pollFirst();
            }
            if (q.size() >= maxRequests) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }
}
