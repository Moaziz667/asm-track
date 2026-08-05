package com.asm.delivery.service.analytics;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.service.SystemSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When an operational exception stops being one.
 *
 * <p>Nothing here used to retire an exception. A partial delivery from three weeks ago, whose
 * remainder had long since been re-imported and delivered, still sat on the dispatch desk and still
 * counted in the badge. A number that can only climb is a number nobody reads, so the alert that
 * mattered arrived among forty that did not.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExceptionRetirementTest {

    @Mock SystemSettingsService settings;

    private ExceptionClassifier classifier() {
        return new ExceptionClassifier(settings);
    }

    private static final LocalDateTime ATTEMPT_ENDED = LocalDateTime.of(2026, 8, 1, 17, 0);

    private static Delivery attempt(DeliveryStatus status, String saleRef) {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setErpExternalRef(saleRef);
        order.setClientName("Boulangerie El Manar");

        Delivery d = new Delivery();
        d.setId(UUID.randomUUID());
        d.setStatus(status);
        d.setOrder(order);
        d.setCreatedAt(ATTEMPT_ENDED.minusDays(1));
        d.setUpdatedAt(ATTEMPT_ENDED);
        if (status == DeliveryStatus.FAILED) d.setFailedAt(ATTEMPT_ENDED);
        else d.setCompletedAt(ATTEMPT_ENDED);
        return d;
    }

    private Object classify(Delivery d, Map<String, LocalDateTime> latestPerRef) {
        return classifier().toExceptionItem(d, Map.of(), Map.of(), Map.of(),
                ATTEMPT_ENDED.plusDays(1), latestPerRef);
    }

    @Test
    @DisplayName("a partial whose reliquat has been re-imported is no longer the dispatcher's problem")
    void partialWithFollowUpRetires() {
        Delivery partial = attempt(DeliveryStatus.PARTIALLY_DELIVERED, "S00322");

        assertThat(classify(partial, Map.of("S00322", ATTEMPT_ENDED.plusHours(2)))).isNull();
    }

    @Test
    @DisplayName("a partial with nothing behind it still is")
    void partialWithoutFollowUpStays() {
        Delivery partial = attempt(DeliveryStatus.PARTIALLY_DELIVERED, "S00322");

        assertThat(classify(partial, Map.of())).isNotNull();
    }

    @Test
    @DisplayName("a failure re-attempted afterwards retires")
    void failureWithFollowUpRetires() {
        Delivery failed = attempt(DeliveryStatus.FAILED, "S00330");

        assertThat(classify(failed, Map.of("S00330", ATTEMPT_ENDED.plusMinutes(30)))).isNull();
    }

    @Test
    @DisplayName("a sibling created before the van set off is a multi-depot split, not a second try")
    void earlierSiblingIsNotAFollowUp() {
        // The shipments of a multi-depot order are all created up front. Counting siblings instead of
        // comparing against the end of the attempt would have retired an exception nobody had touched.
        Delivery partial = attempt(DeliveryStatus.PARTIALLY_DELIVERED, "S00322");

        assertThat(classify(partial, Map.of("S00322", ATTEMPT_ENDED.minusDays(2)))).isNotNull();
    }

    @Test
    @DisplayName("an attempt with no recorded end is left alone rather than guessed at")
    void noEndTimestampKeepsTheException() {
        Delivery partial = attempt(DeliveryStatus.PARTIALLY_DELIVERED, "S00322");
        partial.setCompletedAt(null);
        partial.setFailedAt(null);

        assertThat(classify(partial, Map.of("S00322", ATTEMPT_ENDED.plusHours(2)))).isNotNull();
    }

    @Test
    @DisplayName("a delivery still in the field is never retired by a follow-up")
    void inFlightIsNeverRetired() {
        Delivery inTransit = attempt(DeliveryStatus.IN_TRANSIT, "S00322");

        assertThat(classify(inTransit, Map.of("S00322", ATTEMPT_ENDED.plusHours(2)))).isNotNull();
    }

    @Test
    @DisplayName("a cancelled delivery is not an exception — as this class always claimed")
    void cancelledIsNotSurfaced() {
        // The contract said "CANCELLED is terminal (no action) → never surfaced"; the code returned
        // CRITICAL, and the admin app filtered it out in the browser. A rule enforced on the client
        // is a rule the API does not have.
        Delivery cancelled = attempt(DeliveryStatus.CANCELLED, "S00322");

        assertThat(classify(cancelled, Map.of())).isNull();
    }
}
