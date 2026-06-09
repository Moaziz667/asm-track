package com.asm.delivery.sla;

import com.asm.delivery.entity.*;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.service.DelayCalculationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Locks the SLA contract computed by {@link SlaEvaluator} — the pure, window-anchored decision
 * function {@code (delivery, now) -> (phase, health, dueAt, lateMinutes, attribution)}.
 *
 * <p>Scope note: grace windows, alert dedup and clock re-baselining on replan are
 * {@code SlaStateService} concerns (they persist state); they are not decided here. This suite
 * proves only what the evaluator decides from the delivery's current state — including the exact
 * worked example used in the design docs.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlaEvaluatorTest {

    @Mock RouteStopRepository routeStopRepository;
    @Mock DelayCalculationService delayCalc;
    @Mock SlaPolicy policy;

    SlaEvaluator evaluator;

    /** Reference "now" anchored on the worked example's morning (Africa/Tunis). */
    final LocalDateTime now = LocalDateTime.of(2026, 6, 9, 12, 0);
    final LocalDate today = now.toLocalDate();

    @BeforeEach
    void setUp() {
        evaluator = new SlaEvaluator(routeStopRepository, delayCalc, policy);
        when(policy.planningLeadMinutes()).thenReturn(120);
        when(policy.atRiskWindowMinutes()).thenReturn(30);
    }

    // ════════════════════════════════════════════════════════════════════════════
    @Nested @DisplayName("Planning (ops-owned, anchored on the Odoo scheduled date)")
    class Planning {

        @Test @DisplayName("on track when far from the scheduled day")
        void onTrack_whenFar() {
            Delivery d = delivery(DeliveryStatus.UNSCHEDULED, orderScheduled(now.plusDays(5).withHour(12)));
            SlaEvaluator.Result r = evaluator.evaluate(d, now);
            assertThat(r.phase()).isEqualTo(SlaPhase.PLANNING);
            assertThat(r.health()).isEqualTo(SlaHealth.ON_TRACK);
            assertThat(r.attributableToDriver()).as("planning delay is never the driver's fault").isFalse();
        }

        @Test @DisplayName("at risk within the planning lead-time of the deadline")
        void atRisk_withinLeadTime() {
            Delivery d = delivery(DeliveryStatus.UNSCHEDULED, orderScheduled(now.plusMinutes(60)));
            assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.AT_RISK);
        }

        @Test @DisplayName("breached once past the scheduled deadline and still unrouted")
        void breached_pastDeadline() {
            Delivery d = delivery(DeliveryStatus.UNSCHEDULED, orderScheduled(now.minusMinutes(10)));
            assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.BREACHED);
        }

        @Test @DisplayName("date-only promise uses end-of-business-day (not midnight)")
        void dateOnlyPromise_usesEndOfBusinessDay() {
            // Scheduled "today" at 00:00 must NOT be breached at noon — the real deadline is EOD 18:00.
            Delivery d = delivery(DeliveryStatus.UNSCHEDULED, orderScheduled(today.atStartOfDay()));
            assertThat(evaluator.evaluate(d, now).health()).isNotEqualTo(SlaHealth.BREACHED);
        }

        @Test @DisplayName("replan re-baselines the clock: a future rescheduledAt overrides a past scheduledAt")
        void replan_reBaselinesOnRescheduledAt() {
            Order replanned = Order.builder().id(UUID.randomUUID())
                    .scheduledAt(now.minusDays(1))      // original promise: yesterday (would breach)
                    .rescheduledAt(now.plusDays(2))      // replanned to the future
                    .build();
            Delivery d = delivery(DeliveryStatus.UNSCHEDULED, replanned);
            assertThat(evaluator.evaluate(d, now).health())
                    .as("the promise itself changed, so the clock legitimately moves")
                    .isEqualTo(SlaHealth.ON_TRACK);
        }

        @Test @DisplayName("no scheduled date → awaiting, never breached")
        void noDate_awaiting() {
            Delivery d = delivery(DeliveryStatus.UNSCHEDULED, Order.builder().id(UUID.randomUUID()).build());
            SlaEvaluator.Result r = evaluator.evaluate(d, now);
            assertThat(r.phase()).isEqualTo(SlaPhase.PLANNING);
            assertThat(r.health()).isEqualTo(SlaHealth.ON_TRACK);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    @Nested @DisplayName("Assignment (driver-owned, anchored on the stop start-window)")
    class Assignment {

        @Test @DisplayName("on track for a FUTURE window — the bug the rebuild killed")
        void onTrack_futureWindow_noFalseOverdue() {
            // 08:00 'now', window starts 13:00 → must be green, not 'overdue'.
            LocalDateTime morning = today.atTime(8, 0);
            RouteStop stop = stop(route(today, null), LocalTime.of(13, 0), null, RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.SCHEDULED, orderScheduled(now));
            stubActiveStop(d, stop);
            assertThat(evaluator.evaluate(d, morning).health()).isEqualTo(SlaHealth.ON_TRACK);
        }

        @Test @DisplayName("breached when past the start window and not picked up")
        void breached_pastWindow() {
            RouteStop stop = stop(route(today, null), now.toLocalTime().minusMinutes(20), null, RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.SCHEDULED, orderScheduled(now));
            stubActiveStop(d, stop);
            SlaEvaluator.Result r = evaluator.evaluate(d, now);
            assertThat(r.phase()).isEqualTo(SlaPhase.ASSIGNMENT);
            assertThat(r.health()).isEqualTo(SlaHealth.BREACHED);
            assertThat(r.attributableToDriver()).isTrue();
        }

        @Test @DisplayName("multi-depot: not breached before the driver could reach the secondary depot")
        void multiDepot_notBreachedBeforeDepotReached() {
            UUID depotA = UUID.randomUUID(), depotB = UUID.randomUUID();
            Route route = route(today, depotA);
            RouteStop deliveryStop = stop(route, now.toLocalTime().minusMinutes(20), null, RouteStopType.DELIVERY, depotB);
            RouteStop pickupB = stop(route, null, null, RouteStopType.PICKUP, depotB);
            pickupB.setEtaAt(now.plusMinutes(30)); // driver not there yet
            Delivery d = delivery(DeliveryStatus.SCHEDULED, orderScheduled(now));
            d.setSourceDepotId(depotB);
            stubActiveStop(d, deliveryStop);
            when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId()))
                    .thenReturn(List.of(pickupB, deliveryStop));
            assertThat(evaluator.evaluate(d, now).health())
                    .as("clock can't run before the driver could physically load at depot B")
                    .isEqualTo(SlaHealth.ON_TRACK);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    @Nested @DisplayName("Departure (picked up, awaiting transit)")
    class Departure {

        @Test @DisplayName("breached when past the window and idle in the depot")
        void breached_pastWindowIdle() {
            RouteStop stop = stop(route(today, null), now.toLocalTime().minusMinutes(20), null, RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.PICKED_UP, orderScheduled(now));
            stubActiveStop(d, stop);
            when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(any())).thenReturn(List.of(stop));
            SlaEvaluator.Result r = evaluator.evaluate(d, now);
            assertThat(r.phase()).isEqualTo(SlaPhase.DEPARTURE);
            assertThat(r.health()).isEqualTo(SlaHealth.BREACHED);
        }

        @Test @DisplayName("multi-depot: departure clock holds while other depots are still loading")
        void holds_whileOtherDepotsLoading() {
            Route route = route(today, UUID.randomUUID());
            RouteStop deliveryStop = stop(route, now.toLocalTime().minusMinutes(20), null, RouteStopType.DELIVERY, null);
            RouteStop pendingPickup = stop(route, null, null, RouteStopType.PICKUP, UUID.randomUUID());
            pendingPickup.setStatus(RouteStopStatus.PENDING); // not yet loaded
            Delivery d = delivery(DeliveryStatus.PICKED_UP, orderScheduled(now));
            stubActiveStop(d, deliveryStop);
            when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId()))
                    .thenReturn(List.of(pendingPickup, deliveryStop));
            assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.ON_TRACK);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    @Nested @DisplayName("Delivery (in transit, anchored on the end-window)")
    class Delivery_ {

        @Test @DisplayName("at risk when the ETA lands beyond the window")
        void atRisk_etaBeyondWindow() {
            RouteStop stop = stop(route(today, null), null, now.toLocalTime().plusMinutes(60), RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.IN_TRANSIT, orderScheduled(now));
            d.setRouteEtaAt(now.plusMinutes(90)); // ETA past the window end
            stubActiveStop(d, stop);
            assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.AT_RISK);
        }

        @Test @DisplayName("breached once now is past the end-window")
        void breached_pastEndWindow() {
            RouteStop stop = stop(route(today, null), null, now.toLocalTime().minusMinutes(5), RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.IN_TRANSIT, orderScheduled(now));
            stubActiveStop(d, stop);
            assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.BREACHED);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    @Nested @DisplayName("Proportional at-risk window (cap at 25% of the slot)")
    class ProportionalAtRisk {

        // A tight 10-min window: 13:00–13:10. Flat 30-min would light amber from 12:40 (before it
        // even starts). Proportional caps at 25% of 10 = ~2 min, so it stays green until the end.
        private Delivery tightWindowInTransit() {
            RouteStop stop = stop(route(today, null), LocalTime.of(13, 0), LocalTime.of(13, 10), RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.IN_TRANSIT, orderScheduled(now));
            stubActiveStop(d, stop);
            return d;
        }

        @Test @DisplayName("green at 13:07 — flat 30-min would have (wrongly) shown amber")
        void green_outsideProportionalWindow() {
            assertThat(evaluator.evaluate(tightWindowInTransit(), today.atTime(13, 7)).health())
                    .isEqualTo(SlaHealth.ON_TRACK);
        }

        @Test @DisplayName("amber at 13:09 — inside the proportional ~2-min window")
        void amber_insideProportionalWindow() {
            assertThat(evaluator.evaluate(tightWindowInTransit(), today.atTime(13, 9)).health())
                    .isEqualTo(SlaHealth.AT_RISK);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    @Nested @DisplayName("Terminal outcomes (resolved badges, fair attribution)")
    class Terminal {

        @Test @DisplayName("delivered late → LATE + lateMinutes + driver-attributed")
        void deliveredLate() {
            RouteStop stop = stop(route(today, null), null, now.toLocalTime().minusMinutes(30), RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.DELIVERED, orderScheduled(now));
            stubActiveStop(d, stop);
            when(delayCalc.calculateStrictStopDelayMinutes(any(), any())).thenReturn(18);
            SlaEvaluator.Result r = evaluator.evaluate(d, now);
            assertThat(r.phase()).isEqualTo(SlaPhase.DELIVERED);
            assertThat(r.health()).isEqualTo(SlaHealth.LATE);
            assertThat(r.lateMinutes()).isEqualTo(18);
            assertThat(r.attributableToDriver()).isTrue();
        }

        @Test @DisplayName("delivered on time → MET")
        void deliveredOnTime() {
            RouteStop stop = stop(route(today, null), null, now.toLocalTime(), RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.DELIVERED, orderScheduled(now));
            stubActiveStop(d, stop);
            when(delayCalc.calculateStrictStopDelayMinutes(any(), any())).thenReturn(-5);
            assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.MET);
        }

        @Test @DisplayName("partial delivery resolves to the PARTIAL phase")
        void partial() {
            RouteStop stop = stop(route(today, null), null, now.toLocalTime(), RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.PARTIALLY_DELIVERED, orderScheduled(now));
            stubActiveStop(d, stop);
            when(delayCalc.calculateStrictStopDelayMinutes(any(), any())).thenReturn(0);
            assertThat(evaluator.evaluate(d, now).phase()).isEqualTo(SlaPhase.PARTIAL);
        }

        @Test @DisplayName("cancelled → resolved, no live health")
        void cancelled() {
            Delivery d = delivery(DeliveryStatus.CANCELLED, orderScheduled(now));
            SlaEvaluator.Result r = evaluator.evaluate(d, now);
            assertThat(r.phase()).isEqualTo(SlaPhase.CANCELLED);
            assertThat(r.health()).isEqualTo(SlaHealth.NONE);
        }

        @Test @DisplayName("failed → carries the failure code in reasonParams")
        void failed() {
            Delivery d = delivery(DeliveryStatus.FAILED, orderScheduled(now));
            d.setFailureCode(FailureCode.CLIENT_ABSENT);
            SlaEvaluator.Result r = evaluator.evaluate(d, now);
            assertThat(r.phase()).isEqualTo(SlaPhase.FAILED);
            assertThat(r.reasonParams()).containsEntry("code", "CLIENT_ABSENT");
        }

        @Test @DisplayName("delivered hours late → LATE, not a false 'on time' (regression: completedAt vs window)")
        void deliveredHoursLate_isNotFalselyOnTime() {
            // Window ends 11:39, delivered 23:49 same day → ~730 min late. Must be LATE.
            RouteStop stop = stop(route(today, null), null, java.time.LocalTime.of(11, 39), RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.DELIVERED, orderScheduled(now));
            stubActiveStop(d, stop);
            when(delayCalc.calculateStrictStopDelayMinutes(any(), any())).thenReturn(730);
            SlaEvaluator.Result r = evaluator.evaluate(d, today.atTime(23, 49));
            assertThat(r.health()).isEqualTo(SlaHealth.LATE);
            assertThat(r.lateMinutes()).isEqualTo(730);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    @Nested @DisplayName("Worked example end-to-end (the design-doc happy path)")
    class WorkedExample {

        // Odoo promises today; dispatcher window 13:00–13:30; one delivery stop.
        // Drive the same delivery through the lifecycle and assert phase + health at each step.
        @Test @DisplayName("import 08:05 → assign → pickup → transit → delivered on time")
        void happyPath() {
            Order order = orderScheduled(today.atTime(18, 0)); // EOD promise
            RouteStop stop = stop(route(today, null), LocalTime.of(13, 0), LocalTime.of(13, 30), RouteStopType.DELIVERY, null);
            Delivery d = delivery(DeliveryStatus.UNSCHEDULED, order);
            stubActiveStop(d, stop);
            when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(any())).thenReturn(List.of(stop));

            // 📥 08:05 Planning — due 16:00 (18:00 − 120m), far away → green
            assertResult(d, today.atTime(8, 5), SlaPhase.PLANNING, SlaHealth.ON_TRACK);

            // 📋 08:30 Assignment — window 13:00 is future → green (no false overdue)
            d.setStatus(DeliveryStatus.SCHEDULED);
            assertResult(d, today.atTime(8, 30), SlaPhase.ASSIGNMENT, SlaHealth.ON_TRACK);

            // 📦 12:50 Departure — picked up, before window, route has no pending pickups → green
            d.setStatus(DeliveryStatus.PICKED_UP);
            assertResult(d, today.atTime(12, 50), SlaPhase.DEPARTURE, SlaHealth.ON_TRACK);

            // 🛣️ 12:55 Delivery — in transit, well before 13:30 end-window → green
            d.setStatus(DeliveryStatus.IN_TRANSIT);
            assertResult(d, today.atTime(12, 55), SlaPhase.DELIVERY, SlaHealth.ON_TRACK);

            // 🎉 13:22 Delivered 8 min early → MET
            d.setStatus(DeliveryStatus.DELIVERED);
            when(delayCalc.calculateStrictStopDelayMinutes(any(), any())).thenReturn(-8);
            assertResult(d, today.atTime(13, 22), SlaPhase.DELIVERED, SlaHealth.MET);
        }

        private void assertResult(Delivery d, LocalDateTime at, SlaPhase phase, SlaHealth health) {
            SlaEvaluator.Result r = evaluator.evaluate(d, at);
            assertThat(r.phase()).as("phase @ %s", at.toLocalTime()).isEqualTo(phase);
            assertThat(r.health()).as("health @ %s", at.toLocalTime()).isEqualTo(health);
        }
    }

    // ── Builders / stubs ──────────────────────────────────────────────────────────

    private void stubActiveStop(Delivery d, RouteStop stop) {
        when(routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId())).thenReturn(Optional.of(stop));
    }

    private Order orderScheduled(LocalDateTime scheduledAt) {
        return Order.builder().id(UUID.randomUUID()).scheduledAt(scheduledAt).build();
    }

    private Delivery delivery(DeliveryStatus status, Order order) {
        return Delivery.builder().id(UUID.randomUUID()).status(status).order(order).build();
    }

    private Route route(LocalDate date, UUID depotId) {
        return Route.builder().id(UUID.randomUUID()).date(date).depotId(depotId).build();
    }

    private RouteStop stop(Route route, LocalTime startWin, LocalTime endWin, RouteStopType type, UUID sourceDepotId) {
        return RouteStop.builder().id(UUID.randomUUID()).route(route)
                .stopType(type).sourceDepotId(sourceDepotId).status(RouteStopStatus.PENDING)
                .startTimeWindow(startWin).endTimeWindow(endWin).build();
    }
}
