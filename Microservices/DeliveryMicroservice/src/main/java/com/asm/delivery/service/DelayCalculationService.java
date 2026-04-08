package com.asm.delivery.service;

import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStop;
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

    /**
     * Calculate delay info for a single stop compared to the manual Time Window.
     * @param stop RouteStop with actualArrivalAt and endTimeWindow
     * @param route Route with startedAt
     * @return DelayInfo with minutes, reason, and status
     */
    public DelayInfo calculateDelay(RouteStop stop, Route route, List<RouteStop> allStops) {
        // If not yet arrived, return null
        if (stop.getActualArrivalAt() == null) {
            return null;
        }

        // Manual Mode: We compare Actual Arrival vs Manual End Time Window
        if (stop.getEndTimeWindow() == null || route.getDate() == null) {
            return null;
        }

        // The "Deadline" is the route date + the manual end time slot
        LocalDateTime manualDeadline = route.getDate().atTime(stop.getEndTimeWindow());
        LocalDateTime actual = stop.getActualArrivalAt();

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
                .delayMinutes(Math.max(0, delayMinutes))
                .delayStatus(delayStatus)
                .delayReason(delayReason)
                .expectedArrival(manualDeadline)
                .actualArrival(actual)
                .build();
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
