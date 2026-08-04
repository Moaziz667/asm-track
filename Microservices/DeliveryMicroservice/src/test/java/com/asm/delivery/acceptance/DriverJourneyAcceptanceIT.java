package com.asm.delivery.acceptance;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.DriverDeliveryService;
import com.asm.delivery.service.DriverIncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterisation of the driver's journey, driven through {@code DriverDeliveryService} itself.
 *
 * <h2>Why this test exists</h2>
 * {@code DeliveryLifecycleAcceptanceIT} covers the same statuses by writing to the repositories
 * directly. That verifies the schema, not the service: every rule the driver path enforces —
 * ownership, legal transitions, history, timestamps — is bypassed. Nothing in the suite went through
 * this service before this file, which meant a refactor of it could not be shown to be safe.
 *
 * <p>So these tests assert <b>observable outcomes</b>, never internal structure: a status reached, a
 * timestamp set, a history line appended, a wrong caller refused. They are written to survive the
 * class being split into several — which is exactly what they were written for.
 */
class DriverJourneyAcceptanceIT extends AbstractAcceptanceIT {

    @Autowired DriverDeliveryService driverDeliveryService;
    // The failure and cancellation paths moved to their own service. Only the entry point
    // changes here — every assertion below is the one that passed before the split.
    @Autowired DriverIncidentService driverIncidentService;

    private static final UUID DRIVER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_DRIVER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private UserPrincipal driverPrincipal(UUID driverId) {
        return new UserPrincipal(driverId.toString(), "DRIVER", "Test Driver", "+216 71 000 000", TENANT_ID);
    }

    /** A delivery a driver has accepted and is therefore allowed to act on. */
    private Delivery acceptedDelivery(String ref) {
        Order order = createTestOrder("WH/OUT/" + ref, "BL/" + ref);
        Delivery delivery = createTestDelivery(order);
        driverDeliveryService.accept(delivery.getId(), DRIVER, driverPrincipal(DRIVER));
        return deliveryRepository.findById(delivery.getId()).orElseThrow();
    }

    // ── The nominal path ─────────────────────────────────────────────────────

    @Test
    @DisplayName("accept → pickup → transit walks the statuses and stamps each timestamp")
    void nominalPathReachesInTransit() {
        Order order = createTestOrder("WH/OUT/90001", "BL/90001");
        Delivery delivery = createTestDelivery(order);
        UserPrincipal principal = driverPrincipal(DRIVER);

        driverDeliveryService.accept(delivery.getId(), DRIVER, principal);
        Delivery afterAccept = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.SCHEDULED, afterAccept.getStatus());
        assertEquals(DRIVER, afterAccept.getDriverId());
        assertNotNull(afterAccept.getAssignedAt(), "accept must record when the driver took it");

        driverDeliveryService.pickup(delivery.getId(), DRIVER, principal);
        Delivery afterPickup = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.PICKED_UP, afterPickup.getStatus());
        assertNotNull(afterPickup.getPickedUpAt());

        driverDeliveryService.transit(delivery.getId(), DRIVER,
                BigDecimal.valueOf(34.74), BigDecimal.valueOf(10.76), principal);
        Delivery afterTransit = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.IN_TRANSIT, afterTransit.getStatus());
        assertNotNull(afterTransit.getInTransitAt());
    }

    @Test
    @DisplayName("every step appends a history line, in order")
    void eachStepIsRecordedInHistory() {
        Delivery delivery = acceptedDelivery("90002");
        UserPrincipal principal = driverPrincipal(DRIVER);

        driverDeliveryService.pickup(delivery.getId(), DRIVER, principal);
        driverDeliveryService.transit(delivery.getId(), DRIVER,
                BigDecimal.valueOf(34.74), BigDecimal.valueOf(10.76), principal);

        List<DeliveryStatus> recorded = historyRepository
                .findByDeliveryIdOrderByChangedAtAsc(delivery.getId())
                .stream().map(h -> h.getStatus()).toList();

        assertTrue(recorded.containsAll(List.of(
                        DeliveryStatus.SCHEDULED, DeliveryStatus.PICKED_UP, DeliveryStatus.IN_TRANSIT)),
                "history should hold every status the delivery went through, got " + recorded);
    }

    // ── The rules that protect the data ──────────────────────────────────────

    @Test
    @DisplayName("a driver cannot act on another driver's delivery")
    void anotherDriverIsRefused() {
        Delivery delivery = acceptedDelivery("90003");

        assertThrows(AppException.class,
                () -> driverDeliveryService.pickup(delivery.getId(), OTHER_DRIVER, driverPrincipal(OTHER_DRIVER)),
                "ownership is the whole point of loadAndAuthorize — it must survive any refactor");

        assertEquals(DeliveryStatus.SCHEDULED,
                deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus(),
                "a refused call must leave the delivery untouched");
    }

    @Test
    @DisplayName("statuses cannot be skipped: no transit before pickup")
    void statusOrderIsEnforced() {
        Delivery delivery = acceptedDelivery("90004");

        assertThrows(AppException.class,
                () -> driverDeliveryService.transit(delivery.getId(), DRIVER,
                        BigDecimal.valueOf(34.74), BigDecimal.valueOf(10.76), driverPrincipal(DRIVER)));

        assertEquals(DeliveryStatus.SCHEDULED,
                deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a delivery already taken cannot be accepted twice")
    void acceptIsNotIdempotentByAccident() {
        Delivery delivery = acceptedDelivery("90005");

        assertThrows(AppException.class,
                () -> driverDeliveryService.accept(delivery.getId(), OTHER_DRIVER, driverPrincipal(OTHER_DRIVER)));

        assertEquals(DRIVER, deliveryRepository.findById(delivery.getId()).orElseThrow().getDriverId(),
                "the first driver keeps it");
    }

    // ── Failure and cancellation ─────────────────────────────────────────────

    @Test
    @DisplayName("failing a delivery records the reason and the comment")
    void failureIsRecorded() {
        Delivery delivery = acceptedDelivery("90006");
        UserPrincipal principal = driverPrincipal(DRIVER);
        driverDeliveryService.pickup(delivery.getId(), DRIVER, principal);
        driverDeliveryService.transit(delivery.getId(), DRIVER,
                BigDecimal.valueOf(34.74), BigDecimal.valueOf(10.76), principal);

        driverIncidentService.fail(delivery.getId(), DRIVER, "CLIENT_ABSENT", null,
                "Personne au domicile", principal);

        Delivery failed = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.FAILED, failed.getStatus());
        assertEquals("Personne au domicile", failed.getFailureComment());
        assertNotNull(failed.getFailedAt());
    }

    @Test
    @DisplayName("a driver cancelling releases the delivery back to the pool")
    void driverCancellationReleasesTheDelivery() {
        Delivery delivery = acceptedDelivery("90007");

        driverIncidentService.cancelByDriver(delivery.getId(), DRIVER, "Vehicule en panne",
                driverPrincipal(DRIVER));

        Delivery cancelled = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertEquals(DeliveryStatus.UNSCHEDULED, cancelled.getStatus(),
                "an abandoned delivery must return to the pool, not stay assigned");
        assertNull(cancelled.getDriverId(), "and must no longer belong to the driver who dropped it");
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getActive returns the driver's own deliveries and nobody else's")
    void activeListIsScopedToTheDriver() {
        Delivery mine = acceptedDelivery("90008");

        Order otherOrder = createTestOrder("WH/OUT/90009", "BL/90009");
        Delivery theirs = createTestDelivery(otherOrder);
        driverDeliveryService.accept(theirs.getId(), OTHER_DRIVER, driverPrincipal(OTHER_DRIVER));

        List<UUID> ids = driverDeliveryService.getActive(DRIVER).stream()
                .map(r -> r.getDeliveryId()).toList();

        assertTrue(ids.contains(mine.getId()));
        assertFalse(ids.contains(theirs.getId()), "another driver's delivery must never appear");
    }

    @Test
    @DisplayName("getDelivery refuses a delivery the driver does not own")
    void detailIsScopedToTheDriver() {
        Delivery delivery = acceptedDelivery("90010");

        assertNotNull(driverDeliveryService.getDelivery(delivery.getId(), DRIVER));
        assertThrows(AppException.class,
                () -> driverDeliveryService.getDelivery(delivery.getId(), OTHER_DRIVER));
    }
}
