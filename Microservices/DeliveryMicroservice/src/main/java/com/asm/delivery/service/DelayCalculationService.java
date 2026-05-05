package com.asm.delivery.service;

import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.repository.RouteStopRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Calculates per-stop delay tracking with clear messaging.
 * Handles: first stop reference (T=0), early arrivals, cascading delays, cancelled routes.
 */
@Service
@Slf4j
public class DelayCalculationService {

    private final RouteStopRepository routeStopRepository;

    public DelayCalculationService(RouteStopRepository routeStopRepository) {
        this.routeStopRepository = routeStopRepository;
    }

    /**
     * Calculate delay info for a single stop compared to the manual Time Window.
     * @param stop RouteStop with actualArrivalAt and endTimeWindow
     * @param route Route with startedAt
     * @return DelayInfo with minutes, reason, and status
     */
    public DelayInfo calculateDelay(RouteStop stop, Route route, List<RouteStop> allStops) {
        // Strict stop delay is evaluated at completion time (T5).
        if (stop.getCompletedAt() == null) {
            return null;
        }

        // Manual Mode: We compare Actual Arrival vs Manual End Time Window
        if (stop.getEndTimeWindow() == null || route.getDate() == null) {
            return null;
        }

        // The "Deadline" is the route date + the manual end time slot
        LocalDateTime manualDeadline = route.getDate().atTime(stop.getEndTimeWindow());
        LocalDateTime actual = stop.getCompletedAt();

        // Calculate delay in minutes relative to the manual slot
        long delaySeconds = java.time.temporal.ChronoUnit.SECONDS.between(manualDeadline, actual);
        int delayMinutes = (int) (delaySeconds / 60);

        String delayStatus;
        String delayReason;

        if (delayMinutes <= 0) {
            delayStatus = "ON_TIME";
            delayReason = "✅ Dans le créneau";
        } else {
            delayStatus = "LATE";
            delayReason = String.format("⚠️ Retard: %d min (Fin créneau: %s)", 
                delayMinutes, stop.getEndTimeWindow().toString());
        }

        return DelayInfo.builder()
                .delayMinutes(delayMinutes)
                .delayStatus(delayStatus)
                .delayReason(delayReason)
                .expectedArrival(manualDeadline)
                .actualArrival(actual)
                .build();
    }

    /** Waiting SLA = T2 - T1 (assignedAt - order.createdAt). */
    public Integer calculateWaitingSlaMinutes(Delivery delivery) {
        if (delivery == null || delivery.getAssignedAt() == null || delivery.getOrder() == null || delivery.getOrder().getCreatedAt() == null) {
            return null;
        }
        return minutesBetween(delivery.getOrder().getCreatedAt(), delivery.getAssignedAt());
    }

    /** Assign SLA = pickedUpAt - routeStartTime (fallback: pickedUpAt - assignedAt). */
    public Integer calculateAssignSlaMinutes(Delivery delivery) {
        if (delivery == null || delivery.getPickedUpAt() == null) {
            return null;
        }

        LocalDateTime routeStartReference = resolveRouteStartReference(delivery);
        if (routeStartReference != null) {
            return minutesBetween(routeStartReference, delivery.getPickedUpAt());
        }

        if (delivery.getAssignedAt() != null) {
            return minutesBetween(delivery.getAssignedAt(), delivery.getPickedUpAt());
        }

        return null;
    }

    private LocalDateTime resolveRouteStartReference(Delivery delivery) {
        if (delivery.getId() == null) {
            return null;
        }

        return routeStopRepository.findByDeliveryId(delivery.getId())
                .map(RouteStop::getRoute)
                .map(route -> {
                    if (route == null) {
                        return null;
                    }
                    if (route.getStartedAt() != null) {
                        return route.getStartedAt();
                    }
                    if (route.getDepartureTime() != null) {
                        return route.getDepartureTime();
                    }
                    if (route.getDate() != null && route.getPlannedStartTime() != null) {
                        return route.getDate().atTime(route.getPlannedStartTime());
                    }
                    return null;
                })
                .orElse(null);
    }

    /** Pickup SLA = T4(stop#1) - T3 (inTransitAt - pickedUpAt). */
    public Integer calculatePickupSlaMinutes(Delivery delivery) {
        if (delivery == null || delivery.getInTransitAt() == null || delivery.getPickedUpAt() == null) {
            return null;
        }
        return minutesBetween(delivery.getPickedUpAt(), delivery.getInTransitAt());
    }

    /** Stop duration = T5 - T4 (completedAt - actualArrivalAt). */
    public Integer calculateStopDurationMinutes(RouteStop stop) {
        if (stop == null || stop.getCompletedAt() == null || stop.getActualArrivalAt() == null) {
            return null;
        }
        return minutesBetween(stop.getActualArrivalAt(), stop.getCompletedAt());
    }

    /** Stop delay = T5 - EW (strict boundary, no buffer). */
    public Integer calculateStrictStopDelayMinutes(RouteStop stop, Route route) {
        if (stop == null || route == null || stop.getCompletedAt() == null || route.getDate() == null || stop.getEndTimeWindow() == null) {
            return null;
        }
        LocalDateTime ew = route.getDate().atTime(stop.getEndTimeWindow());
        return minutesBetween(ew, stop.getCompletedAt());
    }

    /** Stop OK iff SW <= T5 <= EW; Stop KO iff T5 > EW. */
    public String calculateCompletionStatus(RouteStop stop, Route route) {
        if (stop == null || route == null || stop.getCompletedAt() == null || route.getDate() == null || stop.getEndTimeWindow() == null) {
            return null;
        }

        LocalDateTime completedAt = stop.getCompletedAt();
        LocalDateTime ew = route.getDate().atTime(stop.getEndTimeWindow());
        LocalDateTime sw = stop.getStartTimeWindow() != null ? route.getDate().atTime(stop.getStartTimeWindow()) : null;

        if (completedAt.isAfter(ew)) {
            return "KO";
        }
        if (sw != null && completedAt.isBefore(sw)) {
            return "KO";
        }
        return "OK";
    }

    /** Route cumulative delay = Σ max(0, T5 - EW) for completed stops. */
    public Integer calculateCumulativeDelayMinutes(Route route, List<RouteStop> stops) {
        if (route == null || stops == null || stops.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (RouteStop stop : stops) {
            Integer delay = calculateStrictStopDelayMinutes(stop, route);
            if (delay != null && delay > 0) {
                total += delay;
            }
        }
        return total;
    }

    /** Route on-time completion rate using strict EW boundary. */
    public Double calculateOnTimeCompletionRate(Route route, List<RouteStop> stops) {
        if (route == null || stops == null || stops.isEmpty()) {
            return 0.0;
        }
        int completed = 0;
        int onTime = 0;
        for (RouteStop stop : stops) {
            if (stop.getCompletedAt() == null) {
                continue;
            }
            completed++;
            Integer delay = calculateStrictStopDelayMinutes(stop, route);
            if (delay != null && delay <= 0) {
                onTime++;
            }
        }
        if (completed == 0) {
            return 0.0;
        }
        return (onTime * 100.0) / completed;
    }

    private Integer minutesBetween(LocalDateTime from, LocalDateTime to) {
        long minutes = java.time.temporal.ChronoUnit.MINUTES.between(from, to);
        if (minutes > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (minutes < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) minutes;
    }

    /**
     * Get the reference time for delay calculation.
     * - First stop: route.startedAt
     * - Subsequent stops: actual arrival at previous stop
     */
    private LocalDateTime getReference(RouteStop stop, LocalDateTime routeStartTime, List<RouteStop> allStops) {
        if (stop.getStopOrder() == 1) {
            return routeStartTime;
        }

        // Find previous stop by order
        RouteStop previousStop = allStops.stream()
                .filter(s -> s.getStopOrder() == stop.getStopOrder() - 1)
                .findFirst()
                .orElse(null);

        if (previousStop == null || previousStop.getActualArrivalAt() == null) {
            return routeStartTime;  // Fallback
        }

        return previousStop.getActualArrivalAt();
    }

    /**
     * Check if any previous stop in the route has failed delivery.
     */
    private boolean isPreviousFailed(RouteStop stop, List<RouteStop> allStops) {
        return allStops.stream()
                .filter(s -> s.getStopOrder() < stop.getStopOrder())
                .anyMatch(s -> s.getStatus().name().equals("FAILED"));
    }

    /**
     * Calculate route-level start delay: how many minutes late the driver started vs plannedStartTime.
     * @param route Route with plannedStartTime and startedAt
     * @return Delay in minutes (positive = late, null if not yet started or on time)
     */
    public Integer calculateRouteStartDelay(Route route) {
        // If route hasn't started, no delay to report
        if (route.getStartedAt() == null || route.getPlannedStartTime() == null || route.getDate() == null) {
            return null;
        }

        // Build planned start: route.date + route.plannedStartTime
        LocalDateTime plannedStart = route.getDate().atTime(route.getPlannedStartTime());

        // Calculate delay in minutes
        long delaySeconds = java.time.temporal.ChronoUnit.SECONDS.between(plannedStart, route.getStartedAt());
        int delayMinutes = (int) (delaySeconds / 60);

        // Return null if on time or early; otherwise return the delay
        return delayMinutes > 0 ? delayMinutes : null;
    }

    // ─────────────────────────────────────────────────────────────────────────

    public static class DelayInfo {
        public int delayMinutes;        // -ve = early, +ve = late
        public String delayStatus;      // ON_TIME, EARLY, LATE
        public String delayReason;      // Human-readable message
        public LocalDateTime expectedArrival;
        public LocalDateTime actualArrival;

        public DelayInfo(int delayMinutes, String delayStatus, String delayReason,
                        LocalDateTime expectedArrival, LocalDateTime actualArrival) {
            this.delayMinutes = delayMinutes;
            this.delayStatus = delayStatus;
            this.delayReason = delayReason;
            this.expectedArrival = expectedArrival;
            this.actualArrival = actualArrival;
        }

        public static DelayInfoBuilder builder() {
            return new DelayInfoBuilder();
        }

        public static class DelayInfoBuilder {
            private int delayMinutes;
            private String delayStatus;
            private String delayReason;
            private LocalDateTime expectedArrival;
            private LocalDateTime actualArrival;

            public DelayInfoBuilder delayMinutes(int delayMinutes) {
                this.delayMinutes = delayMinutes;
                return this;
            }

            public DelayInfoBuilder delayStatus(String delayStatus) {
                this.delayStatus = delayStatus;
                return this;
            }

            public DelayInfoBuilder delayReason(String delayReason) {
                this.delayReason = delayReason;
                return this;
            }

            public DelayInfoBuilder expectedArrival(LocalDateTime expectedArrival) {
                this.expectedArrival = expectedArrival;
                return this;
            }

            public DelayInfoBuilder actualArrival(LocalDateTime actualArrival) {
                this.actualArrival = actualArrival;
                return this;
            }

            public DelayInfo build() {
                return new DelayInfo(delayMinutes, delayStatus, delayReason, expectedArrival, actualArrival);
            }
        }
    }
}
