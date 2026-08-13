package com.asm.delivery.service;

import com.asm.delivery.dto.response.OptimizeRouteResponse;
import com.asm.delivery.dto.response.RouteStopEtaResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DepotRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.service.route.DeliveryDepots;
import com.asm.delivery.service.route.PrecedenceAwareSequencer;
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

        Map<UUID, Delivery> deliveryMap = loadDeliveryMap(stops);

        // Current order total (for savings calculation)
        double[] currentTotals = computeTotalsWithMatrix(depot, stops, deliveryMap);

        // Build coordinates: depot first, then stops in current order
        List<double[]> points = buildCoordinateList(depot, stops, deliveryMap);

        // Try OSRM Trip API, fall back to nearest-neighbor
        List<Integer> optimizedOrder = resolveOptimizedOrder(points, stops, deliveryMap);

        // Reorder stops according to optimized order
        List<RouteStop> reorderedStops = new ArrayList<>();
        for (int idx : optimizedOrder) {
            reorderedStops.add(stops.get(idx));
        }

        // Calculate ETAs for the suggested order (without saving)
        LocalDateTime departure = resolveDepartureTime(route);
        List<RouteStopEtaResponse> etaList = computeEtaList(depot, reorderedStops, deliveryMap, departure);

        double newTotalDuration = etaList.stream()
                .mapToDouble(s -> s.getDriveDurationSeconds() != null ? s.getDriveDurationSeconds() : 0)
                .sum();
        double newTotalDistance = etaList.stream()
                .mapToDouble(s -> s.getDriveDistanceMeters() != null ? s.getDriveDistanceMeters() : 0)
                .sum();

        String suggestedGeometry = osrmRoutingService
            .routeFullGeometry(buildCoordinateList(depot, reorderedStops, deliveryMap))
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

    // ─── Part 5: POST /routes/{id}/recalculate ────────────────────────────────────

    /**
     * Recalculates ETAs and SLAs for all pending stops from current time (or departure time).
     */
    @Transactional
    public void recalculate(UUID routeId) {
        Route route = getRoute(routeId);
        Depot depot = getDepotForRoute(route);

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        Map<UUID, Delivery> deliveryMap = loadDeliveryMap(stops);
        calculateAndSaveETAs(route, depot, stops, deliveryMap);
    }

    // The legacy per-stop slaStatus refresh was removed: the unified SlaState (SlaEvaluator/
    // SlaStateService) is now the single source of SLA truth. computeSlaStatus(...) below is kept
    // only as an on-demand ETA-vs-deadline calculator used by reporting.

    // ─── ETA computation core ────────────────────────────────────────────────────

    private void calculateAndSaveETAs(Route route, Depot depot,
                                      List<RouteStop> stopsInOrder,
                                      Map<UUID, Delivery> deliveryMap) {
        LocalDateTime departure = resolveDepartureTime(route);

        // Use Table API for all durations in one call (efficient batch)
        List<double[]> points = buildCoordinateList(depot, stopsInOrder, deliveryMap);
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
                stop.setEtaBufferAt(null);
                stop.setDriveDurationSeconds(null);
                stop.setDriveDistanceMeters(null);
            }

            /*
            // ─── Manual Mode: We only use OSRM for trajectories, not ETAs/Planning ───
            currentTime = currentTime.plusSeconds(driveSec);

            stop.setEtaAt(currentTime);

            // Use per-stop buffer (fallback to global default if null)
            int buffer = stop.getBufferMinutes() != null ? stop.getBufferMinutes() : slaBufferMinutes;
            stop.setEtaBufferAt(currentTime.plusMinutes(buffer));

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
            stop.setEtaBufferAt(null);

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
                                                       Map<UUID, Delivery> deliveryMap,
                                                       LocalDateTime departure) {
        List<double[]> points = buildCoordinateList(depot, stopsInOrder, deliveryMap);
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

            result.add(RouteStopEtaResponse.builder()
                    .stopId(stop.getId())
                    .sequenceOrder(i + 1)
                    .etaAt(null)
                    .slaDeadline(null)
                    .slaStatus(null)
                    .driveDurationSeconds(driveSec)
                    .driveDistanceMeters(driveMt)
                    .actualArrivalAt(stop.getActualArrivalAt())
                    .status(stop.getStatus())
                    .dwellMinutes(dwell)
                    .bufferMinutes(stop.getBufferMinutes())
                    .build());

                currentTime = currentTime.plusSeconds(driveSec).plusMinutes(dwell);
        }
        return result;
    }

    // ─── Coordinate list helpers ──────────────────────────────────────────────────

    private List<double[]> buildCoordinateList(Depot depot, List<RouteStop> stops,
                                               Map<UUID, Delivery> deliveryMap) {
        List<double[]> points = new ArrayList<>();
        points.add(new double[]{depot.getLatitude(), depot.getLongitude()});
        
        Set<UUID> depotIds = new HashSet<>();
        for (RouteStop s : stops) {
            if (s.getSourceDepotId() != null) depotIds.add(s.getSourceDepotId());
            Delivery d = s.getDeliveryId() != null ? deliveryMap.get(s.getDeliveryId()) : null;
            if (d != null && d.getSourceDepotId() != null) depotIds.add(d.getSourceDepotId());
        }
        Map<UUID, Depot> depotMap = depotIds.isEmpty() ? Map.of() :
            depotRepository.findAllById(depotIds).stream()
            .collect(Collectors.toMap(Depot::getId, Function.identity()));

        for (RouteStop stop : stops) {
            if (stop.getStopType() == RouteStopType.PICKUP) {
                UUID srcId = stop.getSourceDepotId();
                Depot src = srcId != null ? depotMap.get(srcId) : null;
                if (src != null) {
                    points.add(new double[]{src.getLatitude(), src.getLongitude()});
                } else {
                    points.add(new double[]{depot.getLatitude(), depot.getLongitude()});
                }
            } else {
                Delivery delivery = stop.getDeliveryId() != null ? deliveryMap.get(stop.getDeliveryId()) : null;
                Order order = delivery != null ? delivery.getOrder() : null;
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
        }
        return points;
    }

    private double[] computeTotalsWithMatrix(Depot depot, List<RouteStop> stops,
                                             Map<UUID, Delivery> deliveryMap) {
        List<double[]> points = buildCoordinateList(depot, stops, deliveryMap);
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

    private List<Integer> resolveOptimizedOrder(List<double[]> points, List<RouteStop> stops,
                                                Map<UUID, Delivery> deliveryMap) {
        List<Integer> identity = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) identity.add(i);

        List<Integer> osrmOrder = null;
        Optional<OsrmRoutingService.OptimizedRoute> tripOpt = osrmRoutingService.optimizeTrip(points);
        if (tripOpt.isPresent() && !tripOpt.get().optimizedOrder().isEmpty()) {
            osrmOrder = tripOpt.get().optimizedOrder();
        }

        // The matrix is what makes precedence affordable: one table call prices every pair, so a
        // candidate order can be scored without asking OSRM again.
        Optional<OsrmRoutingService.DurationMatrix> matrixOpt = osrmRoutingService.durationMatrix(points);
        if (matrixOpt.isEmpty()) {
            log.info("OSRM matrix unavailable — keeping the current stop order");
            return osrmOrder != null ? pickupsFirst(osrmOrder, stops) : identity;
        }

        Map<Integer, Set<Integer>> mustFollow = precedenceOf(stops, deliveryMap);
        PrecedenceAwareSequencer sequencer =
                new PrecedenceAwareSequencer(matrixOpt.get().durations(), mustFollow, stops.size());

        // The current order is a candidate too, so a route that is already good is left alone and
        // the suggestion can never come back worse than what the dispatcher is looking at.
        List<Integer> best = sequencer.best(sequencer.candidatesFrom(identity, osrmOrder));
        if (best == null) {
            log.warn("No feasible stop order found for route optimisation — keeping the current one");
            return identity;
        }
        return best;
    }

    /**
     * Which stops must precede which: a delivery follows the pickup of every depot it draws from.
     *
     * <p>Two depots on one delivery is the case that makes this a set rather than a single link —
     * see {@link com.asm.delivery.service.route.DeliveryDepots}.
     */
    private Map<Integer, Set<Integer>> precedenceOf(List<RouteStop> stops, Map<UUID, Delivery> deliveryMap) {
        Map<UUID, Integer> pickupIndexByDepot = new HashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            RouteStop s = stops.get(i);
            if (s.getStopType() == RouteStopType.PICKUP && s.getSourceDepotId() != null) {
                pickupIndexByDepot.put(s.getSourceDepotId(), i);
            }
        }
        if (pickupIndexByDepot.isEmpty()) return Map.of();

        Map<Integer, Set<Integer>> mustFollow = new HashMap<>();
        for (int i = 0; i < stops.size(); i++) {
            RouteStop s = stops.get(i);
            if (s.getStopType() == RouteStopType.PICKUP || s.getDeliveryId() == null) continue;
            Delivery delivery = deliveryMap.get(s.getDeliveryId());
            if (delivery == null) continue;
            Set<Integer> predecessors = new LinkedHashSet<>();
            for (UUID depotId : DeliveryDepots.of(delivery)) {
                Integer pickupIndex = pickupIndexByDepot.get(depotId);
                if (pickupIndex != null) predecessors.add(pickupIndex);
            }
            if (!predecessors.isEmpty()) mustFollow.put(i, predecessors);
        }
        return mustFollow;
    }

    /**
     * The old repair, kept for the case where OSRM answers the trip but not the table: feasible,
     * because every pickup ends up ahead of every delivery, and blunt, because it will drive to a
     * far depot before serving a customer next door.
     */
    private List<Integer> pickupsFirst(List<Integer> order, List<RouteStop> stops) {
        List<Integer> pickups = new ArrayList<>();
        List<Integer> deliveries = new ArrayList<>();
        for (Integer idx : order) {
            if (stops.get(idx).getStopType() == RouteStopType.PICKUP) pickups.add(idx);
            else deliveries.add(idx);
        }
        List<Integer> finalOrder = new ArrayList<>(pickups);
        finalOrder.addAll(deliveries);
        return finalOrder;
    }

    // ─── SLA status computation ───────────────────────────────────────────────────

    public static SlaStatus computeSlaStatus(RouteStop stop, LocalDateTime now) {
        // PICKUP stops are system-reconciled depot loads — they have their own
        // departure/overdue SLA (SlaMonitoringService) and must never be judged
        // against a client time window. Skip window/deadline scoring entirely.
        if (stop.getStopType() == RouteStopType.PICKUP) {
            return SlaStatus.ON_TIME;
        }

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
        if (stop.getEtaBufferAt() != null) {
            if (arrival.isAfter(stop.getEtaBufferAt())) return SlaStatus.LATE;
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

    private Map<UUID, Delivery> loadDeliveryMap(List<RouteStop> stops) {
        List<UUID> deliveryIds = stops.stream()
                .filter(s -> s.getDeliveryId() != null)
                .map(RouteStop::getDeliveryId).toList();
        if (deliveryIds.isEmpty()) return Collections.emptyMap();
        return deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                .collect(Collectors.toMap(Delivery::getId, Function.identity()));
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
