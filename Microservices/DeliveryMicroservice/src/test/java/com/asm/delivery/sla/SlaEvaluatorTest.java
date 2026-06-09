package com.asm.delivery.sla;

import com.asm.delivery.entity.*;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.service.DelayCalculationService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Locks the SLA contract: each phase's window-anchored health + the edge cases that broke the old
 * system (multi-depot guard, terminal lateness, cancelled/failed resolution).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SlaEvaluatorTest {

    @Mock RouteStopRepository routeStopRepository;
    @Mock DelayCalculationService delayCalc;
    @Mock SlaPolicy policy;

    SlaEvaluator evaluator;
    LocalDateTime now;

    @BeforeEach
    void setUp() {
        evaluator = new SlaEvaluator(routeStopRepository, delayCalc, policy);
        when(policy.planningLeadMinutes()).thenReturn(120);
        when(policy.atRiskWindowMinutes()).thenReturn(30);
        now = LocalDateTime.of(2026, 6, 9, 12, 0);
    }

    // ── Planning ──────────────────────────────────────────────────────────────

    @Test void planning_onTrack_whenFarFromScheduledDay() {
        Delivery d = delivery(DeliveryStatus.UNSCHEDULED, orderScheduled(now.plusDays(5).withHour(12)));
        SlaEvaluator.Result r = evaluator.evaluate(d, now);
        assertThat(r.phase()).isEqualTo(SlaPhase.PLANNING);
        assertThat(r.health()).isEqualTo(SlaHealth.ON_TRACK);
        assertThat(r.attributableToDriver()).isFalse();
    }

    @Test void planning_atRisk_withinLeadTime() {
        Delivery d = delivery(DeliveryStatus.UNSCHEDULED, orderScheduled(now.plusMinutes(60)));
        assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.AT_RISK);
    }

    @Test void planning_breached_pastScheduledDeadline() {
        Delivery d = delivery(DeliveryStatus.UNSCHEDULED, orderScheduled(now.minusMinutes(10)));
        assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.BREACHED);
    }

    @Test void planning_dateOnlyPromise_usesEndOfBusinessDay() {
        // Scheduled "today" at midnight must NOT be breached at noon — EOD is 18:00.
        Delivery d = delivery(DeliveryStatus.UNSCHEDULED, orderScheduled(now.toLocalDate().atStartOfDay()));
        assertThat(evaluator.evaluate(d, now).health()).isNotEqualTo(SlaHealth.BREACHED);
    }

    // ── Assignment (window-anchored, multi-depot guard) ─────────────────────────

    @Test void assignment_breached_whenPastStartWindowAndNotPickedUp() {
        RouteStop stop = stop(route(now.toLocalDate(), null), now.toLocalTime().minusMinutes(20), null, RouteStopType.DELIVERY, null);
        Delivery d = delivery(DeliveryStatus.SCHEDULED, orderScheduled(now));
        when(routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId())).thenReturn(Optional.of(stop));
        SlaEvaluator.Result r = evaluator.evaluate(d, now);
        assertThat(r.phase()).isEqualTo(SlaPhase.ASSIGNMENT);
        assertThat(r.health()).isEqualTo(SlaHealth.BREACHED);
        assertThat(r.attributableToDriver()).isTrue();
    }

    @Test void assignment_notBreached_beforeDriverReachesSecondaryDepot() {
        UUID depotA = UUID.randomUUID(), depotB = UUID.randomUUID();
        Route route = route(now.toLocalDate(), depotA);
        RouteStop deliveryStop = stop(route, now.toLocalTime().minusMinutes(20), null, RouteStopType.DELIVERY, depotB);
        RouteStop pickupB = stop(route, null, null, RouteStopType.PICKUP, depotB);
        pickupB.setEtaAt(now.plusMinutes(30)); // driver not there yet
        Delivery d = delivery(DeliveryStatus.SCHEDULED, orderScheduled(now));
        d.setSourceDepotId(depotB);
        when(routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId())).thenReturn(Optional.of(deliveryStop));
        when(routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId())).thenReturn(List.of(pickupB, deliveryStop));
        // Past the window, but the clock can't run yet → not breached.
        assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.ON_TRACK);
    }

    // ── Delivery ────────────────────────────────────────────────────────────────

    @Test void delivery_atRisk_whenEtaBeyondWindow() {
        RouteStop stop = stop(route(now.toLocalDate(), null), null, now.toLocalTime().plusMinutes(60), RouteStopType.DELIVERY, null);
        Delivery d = delivery(DeliveryStatus.IN_TRANSIT, orderScheduled(now));
        d.setRouteEtaAt(now.plusMinutes(90)); // ETA past the window end
        when(routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId())).thenReturn(Optional.of(stop));
        assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.AT_RISK);
    }

    // ── Terminal ────────────────────────────────────────────────────────────────

    @Test void delivered_late_setsLateMinutesAndDriverAttribution() {
        RouteStop stop = stop(route(now.toLocalDate(), null), null, now.toLocalTime().minusMinutes(30), RouteStopType.DELIVERY, null);
        Delivery d = delivery(DeliveryStatus.DELIVERED, orderScheduled(now));
        when(routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId())).thenReturn(Optional.of(stop));
        when(delayCalc.calculateStrictStopDelayMinutes(any(), any())).thenReturn(15);
        SlaEvaluator.Result r = evaluator.evaluate(d, now);
        assertThat(r.phase()).isEqualTo(SlaPhase.DELIVERED);
        assertThat(r.health()).isEqualTo(SlaHealth.LATE);
        assertThat(r.lateMinutes()).isEqualTo(15);
        assertThat(r.attributableToDriver()).isTrue();
    }

    @Test void delivered_onTime_isMet() {
        RouteStop stop = stop(route(now.toLocalDate(), null), null, now.toLocalTime(), RouteStopType.DELIVERY, null);
        Delivery d = delivery(DeliveryStatus.DELIVERED, orderScheduled(now));
        when(routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId())).thenReturn(Optional.of(stop));
        when(delayCalc.calculateStrictStopDelayMinutes(any(), any())).thenReturn(-5);
        assertThat(evaluator.evaluate(d, now).health()).isEqualTo(SlaHealth.MET);
    }

    @Test void cancelled_isResolvedNone() {
        Delivery d = delivery(DeliveryStatus.CANCELLED, orderScheduled(now));
        SlaEvaluator.Result r = evaluator.evaluate(d, now);
        assertThat(r.phase()).isEqualTo(SlaPhase.CANCELLED);
        assertThat(r.health()).isEqualTo(SlaHealth.NONE);
    }

    @Test void failed_carriesFailureCode() {
        Delivery d = delivery(DeliveryStatus.FAILED, orderScheduled(now));
        d.setFailureCode(FailureCode.CLIENT_ABSENT);
        SlaEvaluator.Result r = evaluator.evaluate(d, now);
        assertThat(r.phase()).isEqualTo(SlaPhase.FAILED);
        assertThat(r.reasonParams()).containsEntry("code", "CLIENT_ABSENT");
    }

    // ── Builders ────────────────────────────────────────────────────────────────

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
