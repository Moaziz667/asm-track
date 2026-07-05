package com.asm.delivery.service.dispatch;

import com.asm.delivery.dto.request.AdminExceptionReassignRequest;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.RouteStopType;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration coverage for {@link ExceptionResolutionService#doReassign} — the dispatch-desk
 * "réassigner" flow. This is the counterpart of {@code PickupStopReconcilerTest}: that test locks the
 * multi-depot PICKUP decision matrix (the "brain"); this one locks the reassign *orchestration* (the
 * "hands") — custody, tombstones, handoffs, and the event contract the two driver apps depend on.
 *
 * <p>Test taxonomy (enterprise): each block maps to a class of bug we refuse to ship —
 * <ul>
 *   <li><b>State-machine / precondition guards</b> — an illegal transition must be refused, not half-applied.</li>
 *   <li><b>Custody invariants</b> — {@code pickedUpAt} is the discriminator between a depot re-load and a
 *       driver-to-driver handoff; a reassign must never silently rewrite physical custody.</li>
 *   <li><b>Audit tombstones</b> — a live route keeps a {@code REMOVED_REPLANNED / reason=REASSIGNED}
 *       trace so the closure report can reconstruct history; only drafts hard-delete.</li>
 *   <li><b>Event contract</b> — admin sees "reassigned", but the drivers get the RIGHT prompt
 *       (scheduled vs. handoff vs. plain reassign), because that is what shows on their phones.</li>
 * </ul>
 *
 * <p>These are collaborator-contract tests (Mockito): the persistence and messaging edges are mocked, so
 * they assert the decisions {@code doReassign} makes, not the DB round-trip (that is validated live E2E).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // the orchestration hits repos a variable number of times
class ExceptionResolutionReassignTest {

    @Mock DeliveryRepository deliveryRepo;
    @Mock com.asm.delivery.transport.TransportPort transportPort;
    @Mock com.asm.delivery.repository.DeliveryStatusHistoryRepository historyRepo;
    @Mock com.asm.delivery.repository.OrderRepository orderRepo;
    @Mock RouteRepository routeRepository;
    @Mock RouteStopRepository routeStopRepository;
    @Mock com.asm.delivery.service.AuditLogService auditLogService;
    @Mock com.asm.delivery.service.EventPublisher eventPublisher;
    @Mock com.asm.delivery.service.DelayCalculationService delayCalculationService;
    @Mock com.asm.delivery.service.RouteOptimizationService routeOptimizationService;
    @Mock com.asm.delivery.repository.ZoneRepository zoneRepository;
    @Mock DispatchService dispatchService;
    @Mock com.asm.delivery.service.route.RouteWebSocketService routeWebSocketService;
    @Mock com.asm.delivery.service.OutboxProcessor outboxProcessor;
    @Mock com.asm.delivery.service.route.PickupStopReconciler pickupStopReconciler;
    @Mock com.asm.delivery.service.HandoffService handoffService;
    @Mock com.asm.delivery.sla.SlaStateService slaStateService;
    @Spy  ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks ExceptionResolutionService service;

    private static final UserPrincipal ADMIN =
            new UserPrincipal("admin-1", "ADMIN", "Sonia Dispatch", "+21600000000");

    private UUID deliveryId;
    private UUID driverA;   // current owner
    private UUID driverB;   // reassign target
    private UUID depot;

    @BeforeEach
    void setUp() {
        deliveryId = UUID.randomUUID();
        driverA    = UUID.randomUUID();
        driverB    = UUID.randomUUID();
        depot      = UUID.randomUUID();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. State-machine / precondition guards — refuse illegal moves up front
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Guards")
    class Guards {

        @Test
        @DisplayName("A delivered parcel cannot be reassigned (terminal state)")
        void rejectsTerminalStatus() {
            Delivery d = delivery(DeliveryStatus.DELIVERED, driverA, null, order(true));
            when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));

            assertThatThrownBy(() -> service.doReassign(deliveryId, req(driverB, null, "n"), ADMIN, "A", "B"))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("Reassign is allowed only");

            // Nothing may be mutated on a rejected transition.
            assertThat(d.getDriverId()).isEqualTo(driverA);
            verify(deliveryRepo, never()).save(any());
        }

        @Test
        @DisplayName("An in-field parcel needs a handover note (explicit custody change)")
        void inFieldRequiresHandoverNote() {
            Delivery d = delivery(DeliveryStatus.PICKED_UP, driverA, LocalDateTime.now(), order(false));
            when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));

            assertThatThrownBy(() -> service.doReassign(deliveryId, req(driverB, null, null), ADMIN, "A", "B"))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("handover note is required");

            verify(handoffService, never()).request(any(), any(), any(), any(), any());
            verify(deliveryRepo, never()).save(any());
        }

        @Test
        @DisplayName("Reassigning to the same driver on the same route is a no-op, refused")
        void sameDriverSameRouteRejected() {
            Route routeA = route(RouteStatus.IN_PROGRESS, driverA);
            Delivery d = delivery(DeliveryStatus.SCHEDULED, driverA, null, order(false));
            when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));
            when(routeStopRepository.findActiveByDeliveryIdWithRoute(deliveryId))
                    .thenReturn(Optional.of(stopOn(routeA)));

            // target route null → "same route", same driver → refused
            assertThatThrownBy(() -> service.doReassign(deliveryId, req(driverA, null, null), ADMIN, "A", "A"))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("already assigned to this driver");
        }

        @Test
        @DisplayName("Target route must belong to the selected driver")
        void targetRouteOwnershipMismatchRejected() {
            UUID driverC = UUID.randomUUID();
            Route foreignRoute = route(RouteStatus.IN_PROGRESS, driverC);
            Delivery d = delivery(DeliveryStatus.SCHEDULED, driverA, null, order(false));
            when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));
            when(routeRepository.findById(foreignRoute.getId())).thenReturn(Optional.of(foreignRoute));

            assertThatThrownBy(() ->
                    service.doReassign(deliveryId, req(driverB, foreignRoute.getId(), "n"), ADMIN, "A", "B"))
                    .isInstanceOf(AppException.class)
                    .hasMessageContaining("does not belong to the selected driver");

            verify(deliveryRepo, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Main cases — the reassign flows dispatch runs every day
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Main flows")
    class MainFlows {

        @Test
        @DisplayName("Not-picked cross-driver: source is tombstoned REASSIGNED, plain reassign event, no handoff")
        void notPickedCrossDriver() {
            Route routeA = route(RouteStatus.IN_PROGRESS, driverA);
            Route routeB = route(RouteStatus.IN_PROGRESS, driverB);
            RouteStop sourceStop = stopOn(routeA);
            Delivery d = delivery(DeliveryStatus.SCHEDULED, driverA, /*pickedUpAt*/ null, order(false));

            when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));
            when(routeStopRepository.findActiveByDeliveryIdWithRoute(deliveryId)).thenReturn(Optional.of(sourceStop));
            stubRoutes(routeA, routeB);

            Set<UUID> touched = service.doReassign(deliveryId, req(driverB, routeB.getId(), "swap"), ADMIN, "A", "B");

            // ownership + status transition
            assertThat(d.getDriverId()).isEqualTo(driverB);
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SCHEDULED);
            assertThat(d.getPickedUpAt()).isNull(); // never collected → stays null

            // audit tombstone on the live source route
            assertThat(sourceStop.getStatus()).isEqualTo(RouteStopStatus.REMOVED_REPLANNED);
            assertThat(sourceStop.getRemovedReason()).isEqualTo("REASSIGNED");
            verify(routeStopRepository, never()).delete(sourceStop);

            // event contract: plain reassign (drivers notified), NO handoff
            verify(eventPublisher).publishDeliveryReassigned(any(), eq(d), eq(driverA), eq(driverB));
            verify(handoffService, never()).request(any(), any(), any(), any(), any());

            // both routes flagged for pickup reconciliation
            assertThat(touched).contains(routeA.getId(), routeB.getId());
        }

        @Test
        @DisplayName("In-field cross-driver: pickedUpAt KEPT, handoff opened, reassign event marked silent")
        void inFieldCrossDriverOpensHandoff() {
            Route routeA = route(RouteStatus.IN_PROGRESS, driverA);
            Route routeB = route(RouteStatus.IN_PROGRESS, driverB);
            RouteStop sourceStop = stopOn(routeA);
            LocalDateTime pickedAt = LocalDateTime.now().minusHours(1);
            Delivery d = delivery(DeliveryStatus.IN_TRANSIT, driverA, pickedAt, order(false));

            when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));
            when(routeStopRepository.findActiveByDeliveryIdWithRoute(deliveryId)).thenReturn(Optional.of(sourceStop));
            stubRoutes(routeA, routeB);

            service.doReassign(deliveryId, req(driverB, routeB.getId(), "customer not home, hand to B"), ADMIN, "A", "B");

            // CUSTODY INVARIANT: the parcel is physically on driver A — pickedUpAt must survive so the
            // report/SLA know it was collected. It changes hands by handoff, not a fresh depot load.
            assertThat(d.getPickedUpAt()).isEqualTo(pickedAt);
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SCHEDULED);
            assertThat(d.getDriverId()).isEqualTo(driverB);

            // a handoff is opened A → B with the note
            verify(handoffService).request(eq(deliveryId), eq(driverA), eq(driverB), eq(ADMIN), eq("customer not home, hand to B"));
            // drivers get the handoff prompt via the handoff, not a duplicate "new delivery"/"removed" pair
            verify(eventPublisher).publishDeliveryReassigned(any(), eq(d), eq(driverA), eq(driverB), eq(false));
        }

        @Test
        @DisplayName("Assign from the pool onto an active route: clean 'scheduled' event, no handoff, no tombstone")
        void assignFromPoolToActiveRoute() {
            Route routeB = route(RouteStatus.IN_PROGRESS, driverB);
            Delivery d = delivery(DeliveryStatus.UNSCHEDULED, /*no driver*/ null, null, order(false));

            when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));
            // not on any route yet (pool) → default empty Optional from findActive...
            stubRoutes(routeB);

            service.doReassign(deliveryId, req(driverB, routeB.getId(), null), ADMIN, null, "B");

            assertThat(d.getDriverId()).isEqualTo(driverB);
            assertThat(d.getStatus()).isEqualTo(DeliveryStatus.SCHEDULED);

            // first-time assignment → "scheduled", never "reassigned UNKNOWN → B"
            verify(eventPublisher).publishDeliveryScheduled(any(), eq(d), eq(driverB));
            verify(eventPublisher, never()).publishDeliveryReassigned(any(), any(), any(), any());
            verify(handoffService, never()).request(any(), any(), any(), any(), any());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Invariant — draft target is planning, not execution
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Assigning onto a DRAFT route stays UNSCHEDULED (planning) and emits no live driver event")
    void draftTargetStaysUnscheduled() {
        Route draft = route(RouteStatus.DRAFT, driverB);
        Delivery d = delivery(DeliveryStatus.UNSCHEDULED, null, null, order(/*pinned*/ true));

        when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));
        stubRoutes(draft);

        service.doReassign(deliveryId, req(driverB, draft.getId(), null), ADMIN, null, "B");

        // DRAFT = builder planning: the parcel is placed but not yet committed to a driver's live day.
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.UNSCHEDULED);
        assertThat(d.getDriverId()).isEqualTo(driverB);
        verify(eventPublisher, never()).publishDeliveryScheduled(any(), any(), any());
        verify(eventPublisher, never()).publishDeliveryReassigned(any(), any(), any(), any());
        verify(handoffService, never()).request(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Assigning onto a DRAFT route requires the order to be pinned (map coordinates)")
    void draftTargetRequiresPinnedOrder() {
        Route draft = route(RouteStatus.DRAFT, driverB);
        Delivery d = delivery(DeliveryStatus.UNSCHEDULED, null, null, order(/*pinned*/ false));

        when(deliveryRepo.findByIdWithOrder(deliveryId)).thenReturn(Optional.of(d));
        when(routeRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.doReassign(deliveryId, req(driverB, draft.getId(), null), ADMIN, null, "B"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("not pinned");

        verify(deliveryRepo, never()).save(any());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private AdminExceptionReassignRequest req(UUID driverId, UUID targetRouteId, String note) {
        AdminExceptionReassignRequest r = new AdminExceptionReassignRequest();
        r.setDriverId(driverId);
        r.setTargetRouteId(targetRouteId);
        r.setNote(note);
        return r;
    }

    private Delivery delivery(DeliveryStatus status, UUID driverId, LocalDateTime pickedUpAt, Order order) {
        Delivery d = new Delivery();
        d.setId(deliveryId);
        d.setStatus(status);
        d.setDriverId(driverId);
        d.setPickedUpAt(pickedUpAt);
        d.setSourceDepotId(depot);
        d.setOrder(order);
        return d;
    }

    private Order order(boolean pinned) {
        Order o = Order.builder()
                .id(UUID.randomUUID())
                .clientName("Client SARL")
                .sourceDepotId(depot)
                .build();
        if (pinned) {
            o.setDropoffLat(new BigDecimal("36.80"));
            o.setDropoffLng(new BigDecimal("10.18"));
        }
        return o;
    }

    private Route route(RouteStatus status, UUID driverId) {
        Route r = new Route();
        r.setId(UUID.randomUUID());
        r.setStatus(status);
        r.setDriverId(driverId);
        r.setDepotId(depot);
        return r;
    }

    private RouteStop stopOn(Route route) {
        return RouteStop.builder()
                .id(UUID.randomUUID())
                .route(route)
                .deliveryId(deliveryId)
                .stopType(RouteStopType.DELIVERY)
                .status(RouteStopStatus.PENDING)
                .stopOrder(1)
                .build();
    }

    /** Wire routeRepository.findById for each route the orchestration will look up + reconcile. */
    private void stubRoutes(Route... routes) {
        for (Route r : routes) {
            when(routeRepository.findById(r.getId())).thenReturn(Optional.of(r));
        }
        // empty stop lists everywhere unless a test overrides — keeps insertion trivial
        when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(any())).thenReturn(List.of());
    }
}
