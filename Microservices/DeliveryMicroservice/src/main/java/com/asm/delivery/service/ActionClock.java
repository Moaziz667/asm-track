package com.asm.delivery.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * When a driver action actually happened, as opposed to when the server heard about it.
 *
 * The driver app queues writes while offline and replays them on reconnection. Timestamping them
 * with {@code LocalDateTime.now()} recorded the reconnection, so a parcel handed over at 14:10 in a
 * basement was proven delivered at 17:53 in the van — wrong on the proof of delivery, wrong in the
 * SLA verdict, and wrong in the ERP.
 *
 * The app therefore sends {@code X-Client-Timestamp} (ISO-8601, UTC) captured at the tap. A phone's
 * clock is not trustworthy, so the value is only honoured when it is plausible:
 * <ul>
 *   <li>in the past — a future timestamp is a wrong clock, never a real action;</li>
 *   <li>no older than the app's queue TTL — beyond that the entry would have been dead-lettered,
 *       so anything older is a clock that is off, not a genuinely old action.</li>
 * </ul>
 * Anything implausible falls back to server time, which is late but never fabricated.
 */
@Component
@Slf4j
public class ActionClock {

    static final String HEADER = "X-Client-Timestamp";

    /** Small tolerance for clock skew between phone and server. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /** Mirrors the driver app's offline-queue TTL: past it, a write is dead-lettered, not replayed. */
    private static final Duration MAX_AGE = Duration.ofHours(24);

    /** The moment the action was taken, falling back to server time. */
    public LocalDateTime now() {
        String raw = header();
        if (raw == null || raw.isBlank()) return LocalDateTime.now();
        try {
            Instant claimed = Instant.parse(raw.trim());
            Instant now = Instant.now();
            if (claimed.isAfter(now.plus(FUTURE_TOLERANCE))) {
                log.warn("Ignoring {} in the future: {}", HEADER, raw);
                return LocalDateTime.now();
            }
            if (claimed.isBefore(now.minus(MAX_AGE))) {
                log.warn("Ignoring {} older than the queue TTL: {}", HEADER, raw);
                return LocalDateTime.now();
            }
            return LocalDateTime.ofInstant(claimed, ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            log.warn("Unparseable {}: {}", HEADER, raw);
            return LocalDateTime.now();
        }
    }

    private String header() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader(HEADER);
        }
        return null;
    }
}
