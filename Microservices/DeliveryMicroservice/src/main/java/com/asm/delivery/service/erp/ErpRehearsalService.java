package com.asm.delivery.service.erp;

import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RmaRepository;
import com.asm.delivery.service.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * How much of the write path this tenant has actually exercised since its ERP connection last
 * changed.
 *
 * <p>The conformance probe certifies everything that can be certified without side effects — models,
 * fields, methods, access rights — and then stops, because proving that a write works requires
 * writing. This closes that gap the only honest way: by looking at whether real deliveries have
 * completed each outcome <em>and</em> synced back.
 *
 * <p>Deliberately derived rather than recorded. A checklist the integrator ticks himself gets ticked
 * without testing, which is worse than no checklist: it manufactures confidence. Every scenario here
 * is evidence already in the database, so a green mark means a delivery genuinely reached that state
 * and the ERP accepted the write.
 *
 * <p>Scoped to the current connection. Switching to a staging copy — or back to production — clears
 * the board, because a delivery that synced against a different instance says nothing about this one.
 * That is also what makes the rehearsal safe to run on a copy: the moment the integrator points back
 * at production, the evidence resets and nothing false is carried over.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpRehearsalService {

    /**
     * Stamped by {@code InternalErpSettingsController} whenever AppBackend pushes a settings change.
     * Reuses the tenant's existing key-value settings table rather than adding a column for one date.
     */
    public static final String CONNECTION_CHANGED_AT = "erp.connection_changed_at";

    /** A sync ASM considers successful. PENDING_SYNC and SYNC_FAILED are not evidence of anything. */
    private static final String SYNCED = "SYNCED";

    /** One row is all the evidence needed; the queries order by most recent so it is the newest. */
    private static final org.springframework.data.domain.Pageable FIRST =
            org.springframework.data.domain.PageRequest.of(0, 1);

    private final DeliveryRepository deliveryRepository;
    private final RmaRepository rmaRepository;
    private final SystemSettingsService settings;

    /**
     * One scenario of the rehearsal.
     *
     * @param scenario  what was exercised: FULL, PARTIAL, FAILED, RETURN
     * @param done      whether evidence of it exists on the current connection
     * @param evidence  the delivery or RMA that proves it, so the integrator can open and check it
     */
    public record Step(String scenario, boolean done, String evidence) {}

    /**
     * @param since   when the connection last changed; null when it never has, in which case every
     *                delivery ever made counts — there is no earlier connection to confuse it with
     * @param steps   the four scenarios, in the order they are worth doing
     * @param ready   true when all four have happened
     */
    public record Rehearsal(LocalDateTime since, List<Step> steps, boolean ready) {}

    @Transactional(readOnly = true)
    public Rehearsal current() {
        LocalDateTime since = connectionChangedAt();

        List<Step> steps = List.of(
                step("PARTIAL", DeliveryStatus.PARTIALLY_DELIVERED, since),
                step("FULL", DeliveryStatus.DELIVERED, since),
                step("FAILED", DeliveryStatus.FAILED, since),
                returnStep(since));

        return new Rehearsal(since, steps, steps.stream().allMatch(Step::done));
    }

    /**
     * A delivery that reached {@code status} and whose order synced back.
     *
     * <p>Both halves matter. A delivery marked delivered in ASM proves nothing about the ERP; the
     * sync status is what says the write landed. That distinction is the entire point of rehearsing.
     */
    private Step step(String scenario, DeliveryStatus status, LocalDateTime since) {
        return deliveryRepository.findSyncedWithStatus(status, SYNCED, since, FIRST).stream()
                .findFirst()
                .map(d -> new Step(scenario, true, d.getId().toString()))
                .orElseGet(() -> new Step(scenario, false, null));
    }

    /**
     * A return collected and put back into stock.
     *
     * <p>RESTOCKED rather than merely RECEIVED: the ERP write happens when the goods go back on the
     * shelf, so an RMA stopping short of it has not exercised anything.
     */
    private Step returnStep(LocalDateTime since) {
        return rmaRepository.findRestocked(since, FIRST).stream()
                .findFirst()
                .map(r -> new Step("RETURN", true, r.getId().toString()))
                .orElseGet(() -> new Step("RETURN", false, null));
    }

    private LocalDateTime connectionChangedAt() {
        String raw = settings.get(CONNECTION_CHANGED_AT);
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception e) {
            // A malformed stamp must not hide the rehearsal; counting everything is the safe error.
            log.warn("Unreadable {} = '{}' — counting all deliveries", CONNECTION_CHANGED_AT, raw);
            return null;
        }
    }
}
