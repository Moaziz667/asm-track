package com.asm.delivery.service.route;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.RouteStopType;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteStopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for the multi-depot PICKUP reconciler — the invariant that a route carries exactly the
 * depot loads its not-yet-collected deliveries need, on the road as well as in the planner.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // reconcile queries the repos a variable number of times
class PickupStopReconcilerTest {

    @Mock RouteStopRepository routeStopRepository;
    @Mock DeliveryRepository deliveryRepository;

    @InjectMocks PickupStopReconciler reconciler;

    private UUID routeId;
    private UUID homeDepot;   // the route's own depot
    private UUID wh2;         // a remote depot

    @BeforeEach
    void setUp() {
        routeId   = UUID.randomUUID();
        homeDepot = UUID.randomUUID();
        wh2       = UUID.randomUUID();
    }

    // ── Execution: add the load a remote-depot delivery needs ───────────────────

    @Test
    void execution_addsPickupForNewlyNeededRemoteDepot() {
        Route route = route(RouteStatus.IN_PROGRESS);
        RouteStop deliveryStop = deliveryStop(1); // a WH2 delivery, no pickup yet
        Delivery d = delivery(deliveryStop.getDeliveryId(), wh2, DeliveryStatus.SCHEDULED, null);
        stubStops(deliveryStop);
        stubDeliveries(d);

        reconciler.reconcile(route);

        RouteStop added = captureSavedNewPickup();
        assertThat(added).isNotNull();
        assertThat(added.getStopType()).isEqualTo(RouteStopType.PICKUP);
        assertThat(added.getSourceDepotId()).isEqualTo(wh2);
        assertThat(added.getDeliveryId()).isNull();
        assertThat(added.getStatus()).isEqualTo(RouteStopStatus.PENDING);
    }

    @Test
    void execution_addsReturnPickupForHomeDeliveryAddedAfterDeparture() {
        // A home-depot parcel still SCHEDULED (pickedUpAt == null) on an IN_PROGRESS route can only have
        // been added AFTER the start-load (reassign onto a route that already left its depot). It needs a
        // return-trip PICKUP at the home depot — the driver must revisit the depot to load it.
        Route route = route(RouteStatus.IN_PROGRESS);
        RouteStop deliveryStop = deliveryStop(1);
        Delivery d = delivery(deliveryStop.getDeliveryId(), homeDepot, DeliveryStatus.SCHEDULED, null);
        stubStops(deliveryStop);
        stubDeliveries(d);

        reconciler.reconcile(route);

        RouteStop added = captureSavedNewPickup();
        assertThat(added).isNotNull();
        assertThat(added.getSourceDepotId()).isEqualTo(homeDepot);
        assertThat(added.getStopType()).isEqualTo(RouteStopType.PICKUP);
        assertThat(added.getStatus()).isEqualTo(RouteStopStatus.PENDING);
    }

    @Test
    void planning_addsNoPickupForHomeDepotDelivery() {
        // Pre-departure (DRAFT/VALIDATED): a home-depot parcel loads at route start — never a PICKUP stop.
        Route route = route(RouteStatus.VALIDATED);
        RouteStop deliveryStop = deliveryStop(1);
        Delivery d = delivery(deliveryStop.getDeliveryId(), homeDepot, DeliveryStatus.SCHEDULED, null);
        stubStops(deliveryStop);
        stubDeliveries(d);

        reconciler.reconcile(route);

        assertThat(captureSavedNewPickup()).isNull();
    }

    // ── Execution: handoff parcel (pickedUpAt set) must NOT pull a pickup ────────

    @Test
    void execution_addsNoPickupForInFieldHandoffParcel() {
        // In-field reassign downgrades the parcel to SCHEDULED but keeps pickedUpAt: it changes hands by
        // driver-to-driver handoff, not a depot load, so no PICKUP should be created.
        Route route = route(RouteStatus.IN_PROGRESS);
        RouteStop deliveryStop = deliveryStop(1);
        Delivery d = delivery(deliveryStop.getDeliveryId(), wh2, DeliveryStatus.SCHEDULED, LocalDateTime.now());
        stubStops(deliveryStop);
        stubDeliveries(d);

        reconciler.reconcile(route);

        assertThat(captureSavedNewPickup()).isNull();
    }

    // ── Execution: drop an orphaned pickup (soft-delete, PENDING only) ───────────

    @Test
    void execution_softDeletesOrphanedPendingPickup() {
        // The WH2 delivery is gone; only its now-orphaned PENDING pickup remains on the route.
        Route route = route(RouteStatus.IN_PROGRESS);
        RouteStop orphan = pickupStop(wh2, RouteStopStatus.PENDING, 1);
        stubStops(orphan);
        stubDeliveries(); // no deliveries → nothing needs WH2

        reconciler.reconcile(route);

        assertThat(orphan.getStatus()).isEqualTo(RouteStopStatus.REMOVED_REPLANNED);
        assertThat(orphan.getRemovedReason()).isEqualTo("PICKUP_ORPHANED");
        verify(routeStopRepository, never()).delete(orphan); // soft, not hard
    }

    @Test
    void execution_keepsCompletedPickupEvenWhenOrphaned() {
        // A COMPLETED pickup is the frozen past — the driver really loaded there; never touch it.
        Route route = route(RouteStatus.IN_PROGRESS);
        RouteStop completed = pickupStop(wh2, RouteStopStatus.COMPLETED, 1);
        stubStops(completed);
        stubDeliveries();

        reconciler.reconcile(route);

        assertThat(completed.getStatus()).isEqualTo(RouteStopStatus.COMPLETED);
        verify(routeStopRepository, never()).delete(completed);
    }

    // ── Execution edge: a box added after the depot was already loaded ──────────

    @Test
    void execution_addsFreshPickupWhenExistingOneIsCompleted() {
        // Driver already loaded WH2 (pickup COMPLETED), then a new WH2 box is added: it needs a fresh
        // return-trip pickup — the completed one does not cover it.
        Route route = route(RouteStatus.IN_PROGRESS);
        RouteStop completedPickup = pickupStop(wh2, RouteStopStatus.COMPLETED, 1);
        RouteStop newDelivery = deliveryStop(2);
        Delivery d = delivery(newDelivery.getDeliveryId(), wh2, DeliveryStatus.SCHEDULED, null);
        stubStops(completedPickup, newDelivery);
        stubDeliveries(d);

        reconciler.reconcile(route);

        RouteStop added = captureSavedNewPickup();
        assertThat(added).isNotNull();
        assertThat(added.getSourceDepotId()).isEqualTo(wh2);
    }

    // ── Execution edge: shared pickup, one of two deliveries remains ────────────

    @Test
    void execution_keepsSharedPickupWhenOneDeliveryRemains() {
        // Two WH2 deliveries shared one pickup; one left. The pickup is still needed → not dropped, and
        // no duplicate is added.
        Route route = route(RouteStatus.IN_PROGRESS);
        RouteStop pickup = pickupStop(wh2, RouteStopStatus.PENDING, 1);
        RouteStop remaining = deliveryStop(2);
        Delivery d = delivery(remaining.getDeliveryId(), wh2, DeliveryStatus.SCHEDULED, null);
        stubStops(pickup, remaining);
        stubDeliveries(d);

        reconciler.reconcile(route);

        assertThat(pickup.getStatus()).isEqualTo(RouteStopStatus.PENDING); // kept
        assertThat(captureSavedNewPickup()).isNull();                      // no duplicate
    }

    // ── Immutable routes ────────────────────────────────────────────────────────

    @Test
    void closedRoute_isNoOp() {
        Route route = route(RouteStatus.CLOSED);

        reconciler.reconcile(route);

        verifyNoInteractions(routeStopRepository, deliveryRepository);
    }

    // ── Planning: builder path still adds pickups ───────────────────────────────

    @Test
    void planning_addsPickupForRemoteDepotDelivery() {
        Route route = route(RouteStatus.DRAFT);
        RouteStop deliveryStop = deliveryStop(1);
        Delivery d = delivery(deliveryStop.getDeliveryId(), wh2, DeliveryStatus.SCHEDULED, null);
        stubStops(deliveryStop);
        stubDeliveries(d);

        reconciler.reconcile(route);

        RouteStop added = captureSavedNewPickup();
        assertThat(added).isNotNull();
        assertThat(added.getSourceDepotId()).isEqualTo(wh2);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private Route route(RouteStatus status) {
        Route r = new Route();
        r.setId(routeId);
        r.setDepotId(homeDepot);
        r.setStatus(status);
        return r;
    }

    private RouteStop deliveryStop(int order) {
        RouteStop s = new RouteStop();
        s.setId(UUID.randomUUID());
        s.setDeliveryId(UUID.randomUUID());
        s.setStopType(RouteStopType.DELIVERY);
        s.setStatus(RouteStopStatus.PENDING);
        s.setStopOrder(order);
        return s;
    }

    private RouteStop pickupStop(UUID depotId, RouteStopStatus status, int order) {
        RouteStop s = new RouteStop();
        s.setId(UUID.randomUUID());
        s.setDeliveryId(null);
        s.setStopType(RouteStopType.PICKUP);
        s.setSourceDepotId(depotId);
        s.setStatus(status);
        s.setStopOrder(order);
        return s;
    }

    private Delivery delivery(UUID id, UUID sourceDepot, DeliveryStatus status, LocalDateTime pickedUpAt) {
        Delivery d = new Delivery();
        d.setId(id);
        d.setSourceDepotId(sourceDepot);
        d.setStatus(status);
        d.setPickedUpAt(pickedUpAt);
        return d;
    }

    private void stubStops(RouteStop... stops) {
        when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId)).thenReturn(List.of(stops));
    }

    private void stubDeliveries(Delivery... deliveries) {
        when(deliveryRepository.findAllByIdInWithOrder(anyList())).thenReturn(List.of(deliveries));
    }

    /** Returns the newly-built PICKUP stop passed to save() (id still null), or null if none was added. */
    private RouteStop captureSavedNewPickup() {
        ArgumentCaptor<RouteStop> captor = ArgumentCaptor.forClass(RouteStop.class);
        verify(routeStopRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream()
                .filter(s -> s.getStopType() == RouteStopType.PICKUP && s.getId() == null)
                .findFirst()
                .orElse(null);
    }
}
