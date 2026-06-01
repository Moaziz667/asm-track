package com.asm.driver.security;

import com.asm.driver.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-phone rate limiter for the public "resend invite" endpoint.
 * Default cooldown: 60s. The last-sent timestamp is recorded per phone and a
 * subsequent call within the cooldown window is rejected with HTTP 429 and a
 * {@code retryAfterSeconds} hint the client can surface to the user.
 */
@Component
@Slf4j
public class ResendRateLimiter {

    private final ConcurrentHashMap<String, Long> lastSentAtMs = new ConcurrentHashMap<>();
    private final long cooldownMs;

    public ResendRateLimiter(@Value("${invite.resend-cooldown-seconds:60}") long cooldownSeconds) {
        this.cooldownMs = cooldownSeconds * 1000L;
    }

    public void check(String phone) {
        long now = Instant.now().toEpochMilli();
        Long last = lastSentAtMs.get(phone);
        if (last != null) {
            long elapsed = now - last;
            if (elapsed < cooldownMs) {
                long retryAfter = (cooldownMs - elapsed + 999) / 1000;
                log.info("Resend rate-limited for phone={} retryAfter={}s", phone, retryAfter);
                throw AppException.tooManyRequests(
                        "RESEND_RATE_LIMITED:" + retryAfter,
                        retryAfter);
            }
        }
        lastSentAtMs.put(phone, now);
    }

    /** For tests / forced reset. */
    public void reset(String phone) {
        lastSentAtMs.remove(phone);
    }
}
