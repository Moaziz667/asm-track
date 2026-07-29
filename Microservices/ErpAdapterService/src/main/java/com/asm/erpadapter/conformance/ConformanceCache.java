package com.asm.erpadapter.conformance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Holds each tenant's last conformance report for a few minutes.
 *
 * <p>The probe is dozens of round trips to the customer's own Odoo and takes seconds. It was re-run
 * on every render of the compatibility screen — and now that the wizard fetches it to draw its
 * ticks, on every arrival on the page. That is a lot of load on someone else's production ERP to
 * answer a question whose answer changes when their administrator changes something, which is to
 * say almost never.
 *
 * <p>Short by design. A cached report can only be wrong in one direction that matters — saying an
 * instance is fine after it broke — and a few minutes bounds how long that lie can last. The
 * integrator can always force a real probe, which is what the re-check button on the screen does,
 * and the report carries its own {@code checkedAt} so a cached answer never claims to be fresh.
 *
 * <p>In memory and per instance: this is a read-through cache over an idempotent read, so a second
 * replica simply probes once itself. Nothing here is worth a shared cache.
 */
@Component
@Slf4j
public class ConformanceCache {

    /** Long enough to cover a session on the screen, short enough to bound a stale GO. */
    private static final Duration TTL = Duration.ofMinutes(5);

    private record Entry(ConformanceReport report, Instant storedAt) {}

    private final Map<UUID, Entry> byTenant = new ConcurrentHashMap<>();

    /**
     * The tenant's report, probing only if there is nothing fresh enough.
     *
     * @param forceRefresh skip whatever is stored and probe now — what the re-check button asks for
     */
    public ConformanceReport get(UUID tenantId, boolean forceRefresh, Supplier<ConformanceReport> probe) {
        if (tenantId == null) return probe.get();   // no tenant to key on; never cache across tenants

        if (!forceRefresh) {
            Entry hit = byTenant.get(tenantId);
            if (hit != null && Duration.between(hit.storedAt(), Instant.now()).compareTo(TTL) < 0) {
                log.debug("Conformance: cache HIT tenant={} age={}s",
                        tenantId, Duration.between(hit.storedAt(), Instant.now()).toSeconds());
                return hit.report();
            }
        }

        ConformanceReport fresh = probe.get();
        // A failed probe is not an answer worth keeping: caching it would hold the screen on an
        // error for five minutes after the ERP came back.
        if (fresh != null) byTenant.put(tenantId, new Entry(fresh, Instant.now()));
        return fresh;
    }
}
