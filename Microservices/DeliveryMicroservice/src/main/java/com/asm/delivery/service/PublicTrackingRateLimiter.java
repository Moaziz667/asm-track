package com.asm.delivery.service;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Budget for the public tracking view.
 *
 * <h2>What this actually defends against</h2>
 * Not enumeration — the delivery id is a UUIDv4, so guessing one is not a threat anyone can mount.
 * The risk is a <em>leaked</em> link: the response carries the driver's name, phone and live GPS
 * position, so whoever holds the URL can poll it and follow a person around all day. A budget turns
 * continuous surveillance back into occasional consultation, which is what the link is for.
 *
 * <h2>Why the budget is this loose</h2>
 * The tracking page refreshes every 60 seconds and also holds a STOMP subscription, so one honest
 * recipient makes about 10 calls per 10 minutes — and a household behind one NAT address, with a
 * few tabs open, several times that. 120 per 10 minutes sits an order of magnitude above anything a
 * human produces and well below what a script needs to be useful. A limit that occasionally stops a
 * real recipient would be worse than no limit, because it would be switched off.
 */
@Component
public class PublicTrackingRateLimiter extends SlidingWindowRateLimiter {

    public PublicTrackingRateLimiter() {
        super(120, Duration.ofMinutes(10));
    }
}
