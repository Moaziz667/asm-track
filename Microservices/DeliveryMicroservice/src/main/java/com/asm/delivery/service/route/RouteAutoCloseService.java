package com.asm.delivery.service.route;

import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Single source of truth for finalizing a route once all its stops are resolved (was duplicated as
 * {@code maybeAutoCloseRoute} in both RoutePlanningService and RouteExecutionService).
 *
 * <p>The key distinction the old code missed: a route that <em>ran</em> (≥1 stop actually
 * delivered/failed/partial) is <b>CLOSED</b> ("Terminée"); a route that was simply <em>emptied</em>
 * — every stop removed, or no stops at all — never ran, so it is <b>CANCELLED</b> ("Annulée"), not
 * shown as a completed route. Only genuine runs get a closure-report snapshot.</p>
 */
@Service
@RequiredArgsConstructor
public class RouteAutoCloseService {

    private final RouteStopRepository routeStopRepository;
    private final RouteRepository routeRepository;
    private final RouteReportService routeReportService;

    /** Finalize the route iff every stop is resolved (terminal or removed). No-op otherwise. */
    public void finalizeIfResolved(Route route) {
        if (route == null) return;
        if (route.getStatus() != RouteStatus.VALIDATED && route.getStatus() != RouteStatus.IN_PROGRESS) {
            return;
        }
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        boolean allResolved = stops.stream()
                .allMatch(s -> isTerminalStopStatus(s.getStatus()) || isRemovedStatus(s.getStatus()));
        if (!allResolved) return; // (an empty list is vacuously "all resolved" → handled below)

        boolean anyDelivered = stops.stream().anyMatch(s -> isTerminalStopStatus(s.getStatus()));
        route.setStatus(anyDelivered ? RouteStatus.CLOSED : RouteStatus.CANCELLED);
        route.setClosedAt(LocalDateTime.now());
        routeRepository.save(route);

        // A closure report only makes sense for a route that actually ran.
        if (anyDelivered) {
            routeReportService.persistSnapshot(route);
        }
    }

    /** Did the route actually run (≥1 stop reached a terminal outcome)? Used by the manual-close path. */
    public boolean anyStopDelivered(List<RouteStop> stops) {
        return stops.stream().anyMatch(s -> isTerminalStopStatus(s.getStatus()));
    }

    static boolean isTerminalStopStatus(RouteStopStatus status) {
        return status == RouteStopStatus.COMPLETED
                || status == RouteStopStatus.FAILED
                || status == RouteStopStatus.PARTIAL;
    }

    static boolean isRemovedStatus(RouteStopStatus status) {
        return status == RouteStopStatus.REMOVED_REPLANNED
                || status == RouteStopStatus.REMOVED_CANCELLED;
    }
}
