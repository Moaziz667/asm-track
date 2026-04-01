package com.asm.delivery.idempotency;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdempotencyService {

    public record CacheEntry(String fingerprint, Object response, long expiresAtEpochMs) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public CacheEntry get(String scope, String key) {
        String cacheKey = scope + "|" + key;
        CacheEntry entry = cache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expiresAtEpochMs()) {
            cache.remove(cacheKey);
            return null;
        }
        return entry;
    }

    public void put(String scope, String key, String fingerprint, Object response, int ttlSeconds) {
        long expiresAt = System.currentTimeMillis() + Math.max(ttlSeconds, 30) * 1000L;
        cache.put(scope + "|" + key, new CacheEntry(fingerprint, response, expiresAt));
    }
}
