package com.asm.delivery.service;

import com.asm.delivery.dto.response.OptimizeRouteResponse;
import com.asm.delivery.dto.response.RouteStopEtaResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DepotRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.service.route.RouteWebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RouteOptimizationService {

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final DeliveryRepository deliveryRepository;
    private final DepotRepository depotRepository;
    private final OsrmRoutingService osrmRoutingService;
    private final RouteWebSocketService routeWebSocketService;

    @Value("${app.route.default-dwell-minutes:10}")
    private int defaultDwellMinutes;

    // ─── Part 5: POST /routes/{id}/optimize ───────────────────────────────────────

    /**
     * Suggests an optimized stop order without applying it.
     * Returns before/after comparison with savings.
     */
    @Transactional(readOnly = true)
    public OptimizeRouteResponse suggestOptimization(UUID routeId) {
        Route route = getRoute(routeId);
        Depot depot = getDepotForRoute(route);

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        if (stops.isEmpty()) throw AppException.badRequest("Route has no stops to optimize");

        Map<UUID, Order> orderMap = loadOrderMap(stops);

        // Current order total (for savings calculation)
        double[] currentTotals = computeTotalsWithMatrix(depot, stops, orderMap);

        // Build coordinates: depot first, then stops in current order
        List<double[]> points = buildCoordinateList(depot, stops, orderMap);

        // Try OSRM Trip API, fall back to nearest-neighbor
        List<Integer> optimizedOrder = resolveOptimizedOrder(points, stops.size());

        // Reorder stops according to optimized order
        List<RouteStop> reorderedStops = new ArrayList<>();
        for (int idx : optimizedOrder) {
            reorderedStops.add(stops.get(idx));
        }

        // Calculate ETAs for the suggested order (without saving)
        LocalDateTime departure = resolveDepartureTime(route);
        List<RouteStopEtaResponse> etaList = computeEtaList(depot, reorderedStops, orderMap, departure);

        double newTotalDuration = etaList.stream()
                .mapToDouble(s -> s.getDriveDurationSeconds() != null ? s.getDriveDurationSeconds() : 0)
                .sum();
        double newTotalDistance = etaList.stream()
                .mapToDouble(s -> s.getDriveDistanceMeters() != null ? s.getDriveDistanceMeters() : 0)
                .sum();

        String suggestedGeometry = osrmRoutingService
            .routeFullGeometry(buildCoordinateList(depot, reorderedStops, orderMap))
            .orElse(null);

        return OptimizeRouteResponse.builder()
                .optimizedStops(etaList)
                .totalDurationSeconds(newTotalDuration)
                .totalDistanceMeters(newTotalDistance)
            .routeGeometry(suggestedGeometry)
                .savings(OptimizeRouteResponse.SavingsInfo.builder()
                        .durationSavedSeconds(Math.max(0, currentTotals[0] - newTotalDuration))
                        .distanceSavedMeters(Math.max(0, currentTotals[1] - newTotalDistance))
                        .build())
                .build();
    }

    // ─── Part 5: PUT /routes/{id}/apply-optimization ──────────────────────────────

    /**
     * Applies optimized stop order, recalculates ETAs and SLAs, sets isOptimized=true.
     */
    @Transactional
    public void applyOptimization(UUID routeId) {
        Route route = getRoute(routeId);
        Depot depot = getDepotForRoute(route);

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        if (stops.isEmpty()) return;

        Map<UUID, Order> orderMap = loadOrderMap(stops);
        List<double[]> points = buildCoordinateList(depot, stops, orderMap);
        List<Integer> optimizedOrder = resolveOptimizedOrder(points, stops.size());

        // Apply new sequence order
        for (int pos = 0; pos < optimizedOrder.size(); pos++) {
            stops.get(optimizedOrder.get(pos)).setStopOrder(pos + 1);
        }
        routeStopRepository.saveAll(stops);

        // Recalculate ETAs and SLAs on the newly ordered list
        List<RouteStop> reordered = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        calculateAndSaveETAs(route, depot, reordered, orderMap);

        route.setIsOptimized(true);
        routeRepository.save(route);
    }

    // ─── Part 5: PUT /routes/{id}/reorder ─────────────────────────────────────────

    /**
     * Applies a dispatcher-specified stop order, recalculates ETAs/SLAs, sets isOptimized=false.
     */
    @Transactional
    public void applyManualReorder(UUID routeId, List<UUID> stopIdOrder) {
        Route route = getRoute(routeId);
        Depot depot = getDepotForRoute(route);

        List<RouteStop> existing = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        if (existing.size() != stopIdOrder.size()) {
            throw AppException.badRequest("Reorder payload does not match current stop count");
        }

        Map<UUID, RouteStop> byId = existing.stream()
                .collect(Collectors.toMap(RouteStop::getId, Function.identity()));

        for (int i = 0; i < stopIdOrder.size(); i++) {
            RouteStop stop = byId.get(stopIdOrder.get(i));
            if (stop == null) throw AppException.badRequest("Stop id does not belong to this route");
            stop.setStopOrder(i + 1);
        }
        routeStopRepository.saveAll(existing);

        Map<UUID, Order> orderMap = loadOrderMap(existing);
        List<RouteStop> reordered = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);

        // Chronological validation
        java.time.LocalTime lastRef = null;
        for (int i = 0; i < reordered.size(); i++) {
            RouteStop s = reordered.get(i);
            java.time.LocalTime start = s.getStartTimeWindow();
            java.time.LocalTime end = s.getEndTimeWindow();

            if (start != null && lastRef != null && start.isBefore(lastRef)) {
                throw AppException.badRequest("Incohérence temporelle : L'arrêt n°" + (i + 1) + " (" + start + ") commence avant la fin de l'arrêt précédent (" + lastRef + ")");
            }
            if (end != null) lastRef = end;
            else if (s.getEtaAt() != null) lastRef = s.getEtaAt().toLocalTime();
        }

        calculateAndSaveETAs(route, depot, reordered, orderMap);

        route.setIsOptimized(false);
        routeRepository.save(route);

        // Notify driver in real-time when the route they're carrying is reordered
        if (route.getDriverId() != null
                && (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)) {
            routeWebSocketService.notifyDriver(route.getDriverId(), "ROUTE_REORDERED", route.getId(), route.getName());
        }
    }

    // ─── Part 5: POST /routes/{id}/recalculate ────────────────────────────────────

    /**
     * Recalculates ETAs and SLAs for all pending stops from current time (or departure time).
     */
    @Transactional
    public void recalculate(UUID routeId) {
        Route route = getRoute(routeId);
        Depot depot = getDepotForRoute(route);

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        Map<UUID, Order> orderMap = loadOrderMap(stops);
        calculateAndSaveETAs(route, depot, stops, orderMap);
    }

    // ─── SLA status update (called by SlaMonitoringService) ──────────────────────

    /**
     * Recomputes slaStatus for all non-terminal stops of a route based on current time.
     * Returns true if any status changed.
     */
    @Transactional
    public boolean refreshSlaStatuses(UUID routeId) {
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        LocalDateTime now = LocalDateTime.now();
        boolean anyChanged = false;

        for (RouteStop stop : stops) {
            if (stop.getEtaAt() == null || stop.getSlaDeadline() == null) continue;
            if (isTerminal(stop.getStatus())) continue;

            SlaStatus newStatus = computeSlaStatus(stop, now);
            if (newStatus != stop.getSlaStatus()) {
                stop.setSlaStatus(newStatus);
                anyChanged = true;
            }
        }

        if (anyChanged) {
            routeStopRepository.saveAll(stops);
        }
        return anyChanged;
    }

    // ─── ETA computation core ────────────────────────────────────────────────────

    private void calculateAndSaveETAs(Route route, Depot depot,
                                      List<RouteStop> stopsInOrder,
                                      Map<UUID, Order> orderMap) {
        LocalDateTime departure = resolveDepartureTime(route);

        // Use Table API for all durations in one call (efficient batch)
        List<double[]> points = buildCoordinateList(depot, stopsInOrder, orderMap);
        Optional<OsrmRoutingService.DurationMatrix> matrixOpt = osrmRoutingService.durationMatrix(points);

        double[][] durations = null;
        double[][] distances = null;
        if (matrixOpt.isPresent()) {
            durations = matrixOpt.get().durations();
            distances = matrixOpt.get().distances();
        }

        LocalDateTime currentTime = departure;
        int totalDuration = 0;
        int totalDistance = 0;

        for (int i = 0; i < stopsInOrder.size(); i++) {
            RouteStop stop = stopsInOrder.get(i);
            // matrix index: 0=depot, 1=stop[0], 2=stop[1], ...
            int matrixFrom = i;     // previous: depot(0) for first stop, stop[i-1]+1 for rest
            int matrixTo = i + 1;   // current stop

            int driveSec = 0;
            int driveMt = 0;

            if (durations != null && matrixFrom < durations.length && matrixTo < durations[matrixFrom].length) {
                driveSec = (int) Math.round(durations[matrixFrom][matrixTo]);
                driveMt = distances != null ? (int) Math.round(distances[matrixFrom][matrixTo]) : 0;
            } else {
                // OSRM not available — skip duration/distance part
                stop.setEtaAt(null);
                stop.setSlaDeadline(null);
                stop.setDriveDurationSeconds(null);
                stop.setDriveDistanceMeters(null);
            }

            /*
            // ─── Manual Mode: We only use OSRM for trajectories, not ETAs/Planning ───
            currentTime = currentTime.plusSeconds(driveSec);

            stop.setEtaAt(currentTime);

            // Use per-stop buffer (fallback to global default if null)
            int buffer = stop.getBufferMinutes() != null ? stop.getBufferMinutes() : slaBufferMinutes;
            stop.setSlaDeadline(currentTime.plusMinutes(buffer));

            stop.setDriveDurationSeconds(driveSec);
            stop.setDriveDistanceMeters(driveMt);
            stop.setSlaStatus(computeSlaStatus(stop, LocalDateTime.now()));

            totalDuration += driveSec;
            totalDistance += driveMt;

            // Advance time by dwell at this stop (if not yet completed)
            int dwell = stop.getDwellMinutes() != null ? stop.getDwellMinutes() : defaultDwellMinutes;
            currentTime = currentTime.plusMinutes(dwell);
            */
            
            // In Manual Mode, we keep ETA null or use the manual window start if you prefer.
            // For now, we follow the request to disable OSRM-based automatic timing.
            stop.setEtaAt(null); 
            stop.setSlaDeadline(null);
            stop.setSlaStatus(null);
            
            stop.setDriveDurationSeconds(driveSec);
            stop.setDriveDistanceMeters(driveMt);
            totalDuration += driveSec;
            totalDistance += driveMt;
        }

        routeStopRepository.saveAll(stopsInOrder);

        route.setTotalDurationSeconds(totalDuration);
        route.setTotalDistanceMeters(totalDistance);

        // Fetch full OSRM road geometry for route-level map blob
        osrmRoutingService.routeFullGeometry(points).ifPresent(route::setRouteGeometry);

        // Fetch per-leg geometries so RouteTrackingMap can color each leg independently
        List<String> legGeometries = osrmRoutingService.routeLegsGeometry(points);
        for (int i = 0; i < stopsInOrder.size(); i++) {
            if (i < legGeometries.size() && legGeometries.get(i) != null) {
                stopsInOrder.get(i).setRouteGeometry(legGeometries.get(i));
            }
        }
        if (!legGeometries.isEmpty()) {
            routeStopRepository.saveAll(stopsInOrder);
        }

        routeRepository.save(route);
    }

    private List<RouteStopEtaResponse> computeEtaList(Depot depot,
                                                       List<RouteStop> stopsInOrder,
                                                       Map<UUID, Order> orderMap,
                                                       LocalDateTime departure) {
        List<double[]> points = buildCoordinateList(depot, stopsInOrder, orderMap);
        Optional<OsrmRoutingService.DurationMatrix> matrixOpt = osrmRoutingService.durationMatrix(points);

        double[][] durations = null;
        double[][] distances = null;
        if (matrixOpt.isPresent()) {
            durations = matrixOpt.get().durations();
            distances = matrixOpt.get().distances();
        }

        List<RouteStopEtaResponse> result = new ArrayList<>();
        LocalDateTime currentTime = departure;

        for (int i = 0; i < stopsInOrder.size(); i++) {
            RouteStop stop = stopsInOrder.get(i);
            int matrixFrom = i;
            int matrixTo = i + 1;

            int driveSec = 0;
            int driveMt = 0;

            if (durations != null && matrixFrom < durations.length && matrixTo < durations[matrixFrom].length) {
                driveSec = (int) Math.round(durations[matrixFrom][matrixTo]);
                driveMt = distances != null ? (int) Math.round(distances[matrixFrom][matrixTo]) : 0;
            }

            int dwell = stop.getDwellMinutes() != null ? stop.getDwellMinutes() : defaultDwellMinutes;

            // Move time forward by driving duration to get Arrival Time (ETA)
            currentTime = currentTime.plusSeconds(driveSec);

            result.add(RouteStopEtaResponse.builder()
                    .stopId(stop.getId())
                    .sequenceOrder(i + 1)
                    .etaAt(currentTime) // Correctly set the ETA
                    .slaDeadline(null)
                    .slaStatus(null)
                    .driveDurationSeconds(driveSec)
                    .driveDistanceMeters(driveMt)
                    .actualArrivalAt(stop.getActualArrivalAt())
                    .status(stop.getStatus())
                    .dwellMinutes(dwell)
                    .bufferMinutes(stop.getBufferMinutes())
                    .build());

            // Advance time by dwell duration for the next leg
            currentTime = currentTime.plusMinutes(dwell);
        }
        return result;
    }

    // ─── Coordinate list helpers ──────────────────────────────────────────────────

    private List<double[]> buildCoordinateList(Depot depot, List<RouteStop> stops,
                                               Map<UUID, Order> orderMap) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{depot.getLatitude(), depot.getLongitude()});

        for (RouteStop stop : stops) {
            Order order = orderMap.get(stop.getDeliveryId());
            if (order != null && order.getDropoffLat() != null && order.getDropoffLng() != null) {
                points.add(new double[]{
                        order.getDropoffLat().doubleValue(),
                        order.getDropoffLng().doubleValue()
                });
            } else {
                // Use depot coords as placeholder for unpinned stops
                points.add(new double[]{depot.getLatitude(), depot.getLongitude()});
            }
        }
        return points;
    }

    private double[] computeTotalsWithMatrix(Depot depot, List<RouteStop> stops,
                                             Map<UUID, Order> orderMap) {
        List<double[]> points = buildCoordinateList(depot, stops, orderMap);
        Optional<OsrmRoutingService.DurationMatrix> matrixOpt = osrmRoutingService.durationMatrix(points);
        if (matrixOpt.isEmpty()) return new double[]{0, 0};

        double totalDur = 0, totalDist = 0;
        double[][] dur = matrixOpt.get().durations();
        double[][] dist = matrixOpt.get().distances();
        for (int i = 0; i < stops.size(); i++) {
            int from = i;
            int to = i + 1;
            if (from < dur.length && to < dur[from].length) {
                totalDur += dur[from][to];
                totalDist += dist[from][to];
            }
        }
        return new double[]{totalDur, totalDist};
    }

    // ─── TSP order resolution ─────────────────────────────────────────────────────

    private List<Integer> resolveOptimizedOrder(List<double[]> points, int numStops) {
        // Try OSRM Trip API
        Optional<OsrmRoutingService.OptimizedRoute> tripOpt = osrmRoutingService.optimizeTrip(points);
        if (tripOpt.isPresent() && !tripOpt.get().optimizedOrder().isEmpty()) {
            return tripOpt.get().optimizedOrder();
        }

        // Fall back to nearest-neighbor using duration matrix
        log.info("OSRM Trip API unavailable, falling back to nearest-neighbor heuristic");
        Optional<OsrmRoutingService.DurationMatrix> matrixOpt = osrmRoutingService.durationMatrix(points);
        if (matrixOpt.isPresent()) {
            return osrmRoutingService.nearestNeighborOrder(matrixOpt.get().durations(), numStops);
        }

        // No OSRM at all — return identity order
        List<Integer> identity = new ArrayList<>();
        for (int i = 0; i < numStops; i++) identity.add(i);
        return identity;
    }

    // ─── SLA status computation ───────────────────────────────────────────────────

    public static SlaStatus computeSlaStatus(RouteStop stop, LocalDateTime now) {
        LocalDateTime arrival = stop.getActualArrivalAt() != null ? stop.getActualArrivalAt() : now;

        // 1. Manual Window Check (Priority)
        if (stop.getStartTimeWindow() != null && stop.getEndTimeWindow() != null) {
            LocalDateTime routeDate = (stop.getRoute() != null && stop.getRoute().getDate() != null)
                    ? stop.getRoute().getDate().atStartOfDay()
                    : arrival.toLocalDate().atStartOfDay();

            LocalDateTime windowStart = routeDate.toLocalDate().atTime(stop.getStartTimeWindow());
            LocalDateTime windowEnd = routeDate.toLocalDate().atTime(stop.getEndTimeWindow());

            if (arrival.isBefore(windowStart)) return SlaStatus.EARLY;
            if (arrival.isAfter(windowEnd)) return SlaStatus.LATE;
            return SlaStatus.ON_TIME;
        }

        // 2. Deadline-based fallback
        if (stop.getSlaDeadline() != null) {
            if (arrival.isAfter(stop.getSlaDeadline())) return SlaStatus.LATE;
            if (stop.getEtaAt() != null && arrival.isBefore(stop.getEtaAt())) return SlaStatus.EARLY;
            return SlaStatus.ON_TIME;
        }

        return SlaStatus.ON_TIME;
    }

    public static SlaStatus computeSlaStatus(LocalDateTime etaAt, LocalDateTime slaDeadline,
                                             LocalDateTime actualArrivalAt, LocalDateTime now) {
        LocalDateTime reference = actualArrivalAt != null ? actualArrivalAt : now;

        if (slaDeadline != null && reference.isAfter(slaDeadline)) return SlaStatus.LATE;
        if (etaAt != null && (reference.isBefore(etaAt) || reference.isEqual(etaAt))) return SlaStatus.EARLY;

        return SlaStatus.ON_TIME;
    }

    // ─── Utility helpers ──────────────────────────────────────────────────────────

    private Route getRoute(UUID id) {
        return routeRepository.findById(id).orElseThrow(() -> AppException.notFound("Route not found"));
    }

    private Depot getDepotForRoute(Route route) {
        if (route.getDepotId() == null) {
            throw AppException.badRequest("Route has no depot assigned. Assign a depot before optimizing.");
        }
        return depotRepository.findById(route.getDepotId())
                .orElseThrow(() -> AppException.notFound("Depot not found"));
    }

    private Map<UUID, Order> loadOrderMap(List<RouteStop> stops) {
        List<UUID> deliveryIds = stops.stream().map(RouteStop::getDeliveryId).toList();
        if (deliveryIds.isEmpty()) return Collections.emptyMap();
        return deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                .collect(Collectors.toMap(
                        d -> d.getId(),          // keyed by delivery.id = stop.deliveryId
                        d -> d.getOrder() != null ? d.getOrder() : new Order()
                ));
    }

    private LocalDateTime resolveDepartureTime(Route route) {
        if (route.getDepartureTime() != null) return route.getDepartureTime();
        if (route.getDate() != null && route.getPlannedStartTime() != null) {
            return route.getDate().atTime(route.getPlannedStartTime());
        }
        return LocalDateTime.now();
    }

    private static boolean isTerminal(RouteStopStatus status) {
        return status == RouteStopStatus.COMPLETED
                || status == RouteStopStatus.FAILED
                || status == RouteStopStatus.PARTIAL;
    }
}
