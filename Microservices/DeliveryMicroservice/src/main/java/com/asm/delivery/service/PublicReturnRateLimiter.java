package com.asm.delivery.service;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Budget for the public (unauthenticated) return endpoints.
 *
 * <p>Four attempts per day against one delivery: enough to retry a submit that failed, far too few
 * to abuse an open endpoint. Raising a return is a once-in-a-delivery act, so a tight daily window
 * costs a legitimate recipient nothing.
 */
@Component
public class PublicReturnRateLimiter extends SlidingWindowRateLimiter {

    public PublicReturnRateLimiter() {
        super(4, Duration.ofHours(24));
    }
}
