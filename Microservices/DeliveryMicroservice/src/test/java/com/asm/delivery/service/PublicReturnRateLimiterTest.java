package com.asm.delivery.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicReturnRateLimiterTest {

    private final PublicReturnRateLimiter limiter = new PublicReturnRateLimiter();

    @Test
    void allowsUpToTheLimitThenBlocks() {
        String ip = "1.2.3.4";
        UUID delivery = UUID.randomUUID();
        // 4 allowed
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.tryAcquire(ip, delivery)).as("call %d", i + 1).isTrue();
        }
        // 5th blocked
        assertThat(limiter.tryAcquire(ip, delivery)).isFalse();
    }

    @Test
    void tracksIpsSeparately() {
        UUID delivery = UUID.randomUUID();
        for (int i = 0; i < 4; i++) limiter.tryAcquire("10.0.0.1", delivery);
        // A different IP still has its full budget.
        assertThat(limiter.tryAcquire("10.0.0.2", delivery)).isTrue();
    }

    @Test
    void tracksDeliveriesSeparately() {
        String ip = "1.2.3.4";
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        for (int i = 0; i < 4; i++) limiter.tryAcquire(ip, d1);
        assertThat(limiter.tryAcquire(ip, d1)).isFalse();
        // A different delivery from the same IP is independent.
        assertThat(limiter.tryAcquire(ip, d2)).isTrue();
    }

    @Test
    void nullIpDoesNotThrow() {
        UUID delivery = UUID.randomUUID();
        assertThat(limiter.tryAcquire(null, delivery)).isTrue();
    }

    @Test
    void evictEmptyKeepsActiveEntries() {
        // Just exercises the maintenance path — no exception, still enforces the limit afterwards.
        String ip = "9.9.9.9";
        UUID delivery = UUID.randomUUID();
        limiter.tryAcquire(ip, delivery);
        limiter.evictEmpty();
        for (int i = 0; i < 3; i++) limiter.tryAcquire(ip, delivery);
        assertThat(limiter.tryAcquire(ip, delivery)).isFalse();
    }
}
