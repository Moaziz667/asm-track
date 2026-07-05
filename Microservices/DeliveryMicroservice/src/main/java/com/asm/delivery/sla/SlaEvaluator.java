package com.asm.delivery.sla;

import com.asm.delivery.entity.*;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.service.DelayCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single brain. Given a delivery + its active route stop/window + now, it produces one
 * {@code (phase, health, dueAt, lateMinutes, attribution, reasonKey)} — anchored on the
 * dispatcher's real time windows, never on arbitrary elapsed-since-event timers.
 *
 * <p>Window math is delegated to {@link DelayCalculationService} (the long-standing, correct
 * implementation); multi-depot guards (clock only runs once the driver could act) are folded in
 * here, harvested from the old {@code SlaMonitoringService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlaEvaluator {

    /** When the ERP ships only a delivery DATE (time == 00:00), the promise = end of business day. */
    private static final LocalTime END_OF_BUSINESS_DAY = LocalTime.of(18, 0);

    private final RouteStopRepository routeStopRepository;
    private final DelayCalculationService delayCalc;
    private final SlaPolicy policy;

    /** Immutable result of one evaluation; the service maps it onto the persisted {@link SlaState}. */
    public record Result(SlaPhase phase, SlaHealth health, LocalDateTime dueAt, Integer lateMinutes,
                         boolean attributableToDriver, String reasonKey, Map<String, String> reasonParams) {}

    public Result evaluate(Delivery d, LocalDateTime now) {
        DeliveryStatus status = d.getStatus();
        return switch (status) {
            case UNSCHEDULED          -> planning(d, now);
            case SCHEDULED            -> assignment(d, now);
            case PICKED_UP            -> departure(d, now);
            case IN_TRANSIT           -> delivery(d, now);
            case DELIVERED            -> terminalDelivered(d, SlaPhase.DELIVERED, "delivered");
            case PARTIALLY_DELIVERED  -> terminalDelivered(d, SlaPhase.PARTIAL, "partial");
            case FAILED               -> terminalFailed(d);
            case CANCELLED            -> resolved(SlaPhase.CANCELLED, "cancelled", Map.of());
        };
    }

    // ── Live phases ───────────────────────────────────────────────────────────

    /** Planning: the order must be on a route by its scheduled day (EOD Africa/Tunis). Ops-owned. */
    private Result planning(Delivery d, LocalDateTime now) {
        LocalDateTime dueAt = scheduledDeadline(d);
        if (dueAt == null) {
            return new Result(SlaPhase.PLANNING, SlaHealth.ON_TRACK, null, null, false,
                    "planning.awaiting", Map.of());
        }
        SlaHealth health;
        if (now.isAfter(dueAt))                                              health = SlaHealth.BREACHED;
        else if (now.isAfter(dueAt.minusMinutes(policy.planningLeadMinutes()))) health = SlaHealth.AT_RISK;
        else                                                                 health = SlaHealth.ON_TRACK;
        return new Result(SlaPhase.PLANNING, health, dueAt, null, false,
                "planning." + key(health), dateParams(dueAt));
    }

    /** Assignment: must be PICKED_UP in time for the stop's start window. Driver-owned (excl. depot wait). */
    private Result assignment(Delivery d, LocalDateTime now) {
        RouteStop stop = activeStop(d);
        LocalDateTime dueAt = windowStart(stop);
        if (dueAt == null) {
            return new Result(SlaPhase.ASSIGNMENT, SlaHealth.ON_TRACK, null, null, true,
                    "assignment.awaiting", Map.of());
        }
        // Multi-depot: cannot breach before the driver could physically reach the source depot.
        boolean reachable = depotReached(d, stop, now);
        SlaHealth health = reachable ? liveHealth(now, dueAt, null, stop) : SlaHealth.ON_TRACK;
        return new Result(SlaPhase.ASSIGNMENT, health, dueAt, null, true,
                "assignment." + key(health), timeParams(dueAt));
    }

    /** Departure: must leave the depot (IN_TRANSIT) within the window; multi-depot clock waits for last load. */
    private Result departure(Delivery d, LocalDateTime now) {
        RouteStop stop = activeStop(d);
        LocalDateTime dueAt = windowStart(stop);
        // While other depots are still loading, the departure clock must not run.
        if (stop != null && stop.getRoute() != null && stillLoadingOtherDepots(stop.getRoute().getId())) {
            return new Result(SlaPhase.DEPARTURE, SlaHealth.ON_TRACK, dueAt, null, true,
                    "departure.loading", Map.of());
        }
        if (dueAt == null) {
            return new Result(SlaPhase.DEPARTURE, SlaHealth.ON_TRACK, null, null, true,
                    "departure.awaiting", Map.of());
        }
        SlaHealth health = liveHealth(now, dueAt, null, stop);
        return new Result(SlaPhase.DEPARTURE, health, dueAt, null, true,
                "departure." + key(health), timeParams(dueAt));
    }

    /** Delivery: must arrive within the end window; ETA beyond the window flips AT_RISK. Driver-owned. */
    private Result delivery(Delivery d, LocalDateTime now) {
        RouteStop stop = activeStop(d);
        LocalDateTime dueAt = windowEnd(stop);
        if (dueAt == null) {
            return new Result(SlaPhase.DELIVERY, SlaHealth.ON_TRACK, null, null, true,
                    "delivery.awaiting", Map.of());
        }
        LocalDateTime eta = d.getRouteEtaAt() != null ? d.getRouteEtaAt()
                : (stop != null ? stop.getEtaAt() : null);
        SlaHealth health = liveHealth(now, dueAt, eta, stop);
        return new Result(SlaPhase.DELIVERY, health, dueAt, null, true,
                "delivery." + key(health), timeParams(dueAt));
    }

    // ── Terminal ──────────────────────────────────────────────────────────────

    /** Resolved delivery/partial: MET vs LATE measured by completedAt against the end window. */
    private Result terminalDelivered(Delivery d, SlaPhase phase, String prefix) {
        RouteStop stop = activeStop(d);
        Integer late = (stop != null && stop.getRoute() != null)
                ? delayCalc.calculateStrictStopDelayMinutes(stop, stop.getRoute()) : null;
        int lateMin = late != null ? Math.max(0, late) : 0;
        boolean isLate = lateMin > 0;
        return new Result(phase, isLate ? SlaHealth.LATE : SlaHealth.MET,
                stop != null ? windowEnd(stop) : null, lateMin, isLate,
                prefix + (isLate ? ".late" : ".onTime"),
                isLate ? Map.of("minutes", String.valueOf(lateMin)) : Map.of());
    }

    /**
     * Resolved failure: an échec still has an SLA verdict, not a blank "—". Measured like a delivery —
     * by when the failed attempt was recorded against the end window: overdue → BREACHED (Dépassé);
     * within the window → MET (the attempt was punctual, it just didn't succeed).
     */
    private Result terminalFailed(Delivery d) {
        RouteStop stop = activeStop(d);
        Integer late = (stop != null && stop.getRoute() != null)
                ? delayCalc.calculateStrictStopDelayMinutes(stop, stop.getRoute()) : null;
        int lateMin = late != null ? Math.max(0, late) : 0;
        boolean breached = lateMin > 0;
        return new Result(SlaPhase.FAILED, breached ? SlaHealth.BREACHED : SlaHealth.MET,
                stop != null ? windowEnd(stop) : null, breached ? lateMin : null, false,
                "failed", Map.of("code", d.getFailureCode() != null ? d.getFailureCode().name() : "FAILED"));
    }

    private Result resolved(SlaPhase phase, String key, Map<String, String> params) {
        return new Result(phase, SlaHealth.NONE, null, null, false, key, params);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** ON_TRACK → AT_RISK (near dueAt or ETA misses) → BREACHED (past dueAt). */
    private SlaHealth liveHealth(LocalDateTime now, LocalDateTime dueAt, LocalDateTime eta, RouteStop stop) {
        if (now.isAfter(dueAt)) return SlaHealth.BREACHED;
        boolean nearDeadline = now.isAfter(dueAt.minusMinutes(effectiveAtRiskMinutes(stop)));
        boolean etaMisses = eta != null && eta.isAfter(dueAt);
        return (nearDeadline || etaMisses) ? SlaHealth.AT_RISK : SlaHealth.ON_TRACK;
    }

    /**
     * The amber "at-risk" lead-time, capped at 25% of the stop's time window so a tight 10-min slot
     * doesn't light amber from the very first second the way the flat configured value would. Falls
     * back to the configured {@code at-risk-window-minutes} when the window span is unknown.
     */
    private long effectiveAtRiskMinutes(RouteStop stop) {
        long configured = policy.atRiskWindowMinutes();
        LocalDateTime ws = windowStart(stop);
        LocalDateTime we = windowEnd(stop);
        if (ws != null && we != null) {
            long span = java.time.Duration.between(ws, we).toMinutes();
            if (span > 0) return Math.max(1, Math.min(configured, span / 4));
        }
        return configured;
    }

    /** The ERP commitment as an instant: a bare date (00:00) becomes EOD-business; a real time stays. */
    private LocalDateTime scheduledDeadline(Delivery d) {
        LocalDateTime sched = d.getOrder() != null ? d.getOrder().effectiveScheduledAt() : null;
        if (sched == null) return null;
        return sched.toLocalTime().equals(LocalTime.MIDNIGHT)
                ? sched.toLocalDate().atTime(END_OF_BUSINESS_DAY)
                : sched;
    }

    private LocalDateTime windowStart(RouteStop s) {
        if (s == null || s.getRoute() == null || s.getRoute().getDate() == null || s.getStartTimeWindow() == null) return null;
        return s.getRoute().getDate().atTime(s.getStartTimeWindow());
    }

    private LocalDateTime windowEnd(RouteStop s) {
        if (s == null || s.getRoute() == null || s.getRoute().getDate() == null || s.getEndTimeWindow() == null) return null;
        return s.getRoute().getDate().atTime(s.getEndTimeWindow());
    }

    private RouteStop activeStop(Delivery d) {
        if (d.getId() == null) return null;
        return routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId()).orElse(null);
    }

    /** Multi-depot: for a secondary-depot delivery, true once the driver has reached (or is due at) that depot. */
    private boolean depotReached(Delivery d, RouteStop stop, LocalDateTime now) {
        if (stop == null || stop.getRoute() == null || d.getSourceDepotId() == null) return true;
        // Home-depot parcels normally load at route start (no PICKUP stop) → "reached" immediately.
        // But a parcel added AFTER departure (reassign onto a route that already left its depot) gets a
        // return-trip PICKUP even at the home depot — so we DON'T short-circuit home here; the general
        // gating below returns "reached" only once that load stop is done. No PICKUP ⇒ pickup==null ⇒
        // reached, so a normal home delivery is unaffected.
        RouteStop pickup = pickupStopFor(stop.getRoute().getId(), d.getSourceDepotId());
        if (pickup == null) return true;
        if (pickup.getCompletedAt() != null) return true;
        return pickup.getEtaAt() == null || !pickup.getEtaAt().isAfter(now);
    }

    private RouteStop pickupStopFor(UUID routeId, UUID depotId) {
        return routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId).stream()
                .filter(s -> s.getStopType() == RouteStopType.PICKUP && depotId.equals(s.getSourceDepotId()))
                .findFirst().orElse(null);
    }

    private boolean stillLoadingOtherDepots(UUID routeId) {
        List<RouteStop> pickups = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId).stream()
                .filter(s -> s.getStopType() == RouteStopType.PICKUP).toList();
        return !pickups.isEmpty() && pickups.stream()
                .anyMatch(p -> p.getStatus() != RouteStopStatus.COMPLETED);
    }

    private String key(SlaHealth h) {
        return switch (h) {
            case AT_RISK -> "atRisk";
            case BREACHED -> "breached";
            default -> "onTrack";
        };
    }

    private Map<String, String> timeParams(LocalDateTime dueAt) {
        return Map.of("dueAt", dueAt.toString());
    }

    private Map<String, String> dateParams(LocalDateTime dueAt) {
        return Map.of("dueDate", dueAt.toLocalDate().toString(), "dueAt", dueAt.toString());
    }
}
