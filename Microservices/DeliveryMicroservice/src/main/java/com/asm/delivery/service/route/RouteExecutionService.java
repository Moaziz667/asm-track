package com.asm.delivery.service.route;

import com.asm.delivery.dto.response.RouteResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RouteExecutionService {

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryStatusHistoryRepository deliveryStatusHistoryRepository;
    private final AuditLogService auditLogService;
    private final RoutePlanningService routePlanningService;
    private final TransportPort transportPort;
    private final DelayCalculationService delayCalculationService;
    private final com.asm.delivery.service.VehicleInspectionService inspectionService;
    private final RouteReportService routeReportService;
    private final RouteAutoCloseService routeAutoCloseService;
    private final RouteWebSocketService routeWebSocketService;
    private final DepotRepository depotRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional
    public RouteResponse close(UUID routeId) {
        Route route = getRoute(routeId);
        if (route.getStatus() != RouteStatus.IN_PROGRESS) {
            throw AppException.badRequest("Admin can close only in-progress routes after driver termination");
        }

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        List<RouteStop> blocking = stops.stream()
                .filter(s -> !isTerminalStopStatus(s.getStatus()) && !isRemovedStatus(s.getStatus()))
                .toList();
        if (!blocking.isEmpty()) {
            String blockingStatuses = blocking.stream()
                    .map(s -> "#" + s.getStopOrder() + " " + s.getStatus())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            throw AppException.badRequest("Cannot close: stops still active — " + blockingStatuses);
        }

        // A route that never delivered anything (all stops removed) is cancelled, not "completed".
        boolean anyDelivered = routeAutoCloseService.anyStopDelivered(stops);
        route.setStatus(anyDelivered ? RouteStatus.CLOSED : RouteStatus.CANCELLED);
        route.setClosedAt(LocalDateTime.now());
        routeRepository.save(route);
        auditLogService.logAction(null, anyDelivered ? "CLOSE_ROUTE" : "CANCEL_ROUTE", "ROUTE", routeId.toString(),
                java.util.Map.of("tournee", route.getName() != null ? route.getName() : routeId.toString(), "action", "Cloture manuelle par admin"));
        // A closure report only makes sense for a route that actually ran.
        if (anyDelivered) routeReportService.persistSnapshot(route);
        return routePlanningService.get(route.getId());
    }

    @Transactional(readOnly = true)
    public RouteResponse getTodayForDriver(UUID driverId) {
        LocalDate today = LocalDate.now();
        List<RouteStatus> activeStatuses = List.of(RouteStatus.VALIDATED, RouteStatus.IN_PROGRESS);

        // Primary: today's route (VALIDATED or IN_PROGRESS)
        var todayRoute = routeRepository.findByDriverIdAndDateAndStatusIn(driverId, today, activeStatuses)
                .stream().findFirst();
        if (todayRoute.isPresent()) {
            return toResponse(todayRoute.get());
        }

        // Fallback: yesterday's route still IN_PROGRESS (driver crossed midnight)
        var yesterdayRoute = routeRepository.findByDriverIdAndDateAndStatusIn(
                driverId, today.minusDays(1), List.of(RouteStatus.IN_PROGRESS))
                .stream().findFirst();
        return yesterdayRoute.map(this::toResponse).orElse(null);
    }

    @Transactional
    public RouteResponse start(UUID routeId, UUID driverId, UserPrincipal principal) {
        Route route = getRoute(routeId);
        ensureDriverOwnsRoute(route, driverId);

        if (route.getStatus() != RouteStatus.VALIDATED) {
            throw AppException.badRequest("Only validated routes can be started");
        }

        route.setStatus(RouteStatus.IN_PROGRESS);
        LocalDateTime now = LocalDateTime.now();
        route.setStartedAt(now);
        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        auditLogService.logAction(principal, "START_ROUTE", "ROUTE", routeId.toString(),
                java.util.Map.of("chauffeur", driverName, "tournee", route.getName() != null ? route.getName() : routeId.toString(),
                       "action", "Demarrage de la tournee"));
        routeRepository.save(route);

        // Auto-pickup only HOME-depot deliveries (loaded at the origin depot). Deliveries sourced
        // from another depot stay SCHEDULED until the driver confirms that depot's PICKUP stop.
        UUID homeDepotId = route.getDepotId();
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        for (RouteStop stop : stops) {
            if (stop.getStopType() == RouteStopType.PICKUP || stop.getDeliveryId() == null) continue;
            deliveryRepository.findById(stop.getDeliveryId()).ifPresent(delivery -> {
                boolean homeSourced = delivery.getSourceDepotId() == null
                        || (homeDepotId != null && homeDepotId.equals(delivery.getSourceDepotId()));
                // pickedUpAt != null ⟹ the parcel is already in the field (reassigned in-field via a
                // driver-to-driver handoff): it changes hands through the handoff, it is NOT re-loaded at
                // this route's departure. Skipping it keeps the handoff as the custody path, avoids a
                // spurious "auto-pickup", and preserves the original pickup time. Same discriminator the
                // multi-depot PICKUP reconciler uses.
                if (delivery.getStatus() == DeliveryStatus.SCHEDULED && homeSourced && delivery.getPickedUpAt() == null) {
                    delivery.setStatus(DeliveryStatus.PICKED_UP);
                    delivery.setPickedUpAt(now);
                    delivery.setAssignSlaMinutes(delayCalculationService.calculateAssignSlaMinutes(delivery));
                    deliveryRepository.save(delivery);
                    appendHistory(delivery, DeliveryStatus.PICKED_UP, driverId.toString(), Role.DRIVER, "ROUTE_STARTED_AUTO_PICKUP", Map.of("driverId", driverId.toString()));
                    syncStopFromDelivery(delivery.getId(), delivery.getStatus(), now, "Route started and package auto-picked up");
                }
            });
        }

        int deliveryStops = (int) stops.stream()
                .filter(s -> s.getStopType() == RouteStopType.DELIVERY && !isRemovedStatus(s.getStatus()))
                .count();
        routeWebSocketService.notifyRouteStarted(route.getId(), route.getName(), driverId,
                driverName, deliveryStops, now);

        return toResponse(route);
    }

    @Transactional
    public RouteResponse arrive(UUID routeId, UUID stopId, UUID driverId, UserPrincipal principal) {
        Route route = getRoute(routeId);
        ensureDriverOwnsRoute(route, driverId);

        if (route.getStatus() != RouteStatus.IN_PROGRESS && route.getStatus() != RouteStatus.VALIDATED) {
            throw AppException.badRequest("Route is not active");
        }

        RouteStop stop = routeStopRepository.findByRouteIdAndId(routeId, stopId)
                .orElseThrow(() -> AppException.notFound("Route stop not found"));

        if (isTerminalStopStatus(stop.getStatus())) {
            throw AppException.badRequest("Stop is already completed — cannot mark as arrived");
        }
        if (stop.getStatus() == RouteStopStatus.ARRIVED) {
            return toResponse(route); // idempotent
        }

        stop.setStatus(RouteStopStatus.ARRIVED);
        stop.setArrivedAt(LocalDateTime.now());
        stop.setActualArrivalAt(stop.getArrivedAt());
        routeStopRepository.save(stop);
        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        auditLogService.logAction(principal, "ARRIVE_STOP", "ROUTE", routeId.toString(),
                java.util.Map.of("chauffeur", driverName, "tournee", route.getName() != null ? route.getName() : routeId.toString(),
                       "stop", stop.getStopOrder(), "action", "Arrivee au point d'arret"));

        if (route.getStatus() == RouteStatus.VALIDATED) {
            route.setStatus(RouteStatus.IN_PROGRESS);
            route.setStartedAt(LocalDateTime.now());
            routeRepository.save(route);
        }

        return toResponse(route);
    }

    /**
     * Confirm a multi-depot PICKUP stop: loads (advances to PICKED_UP) every still-scheduled
     * delivery on the route sourced from this stop's depot, then marks the pickup stop completed.
     * Idempotent: a pickup stop already completed just returns the current route.
     */
    @Transactional
    public RouteResponse confirmPickup(UUID routeId, UUID stopId, UUID driverId, UserPrincipal principal) {
        Route route = getRoute(routeId);
        ensureDriverOwnsRoute(route, driverId);
        if (route.getStatus() != RouteStatus.IN_PROGRESS && route.getStatus() != RouteStatus.VALIDATED) {
            throw AppException.badRequest("Route is not active");
        }

        RouteStop pickupStop = routeStopRepository.findByRouteIdAndId(routeId, stopId)
                .orElseThrow(() -> AppException.notFound("Route stop not found"));
        if (pickupStop.getStopType() != RouteStopType.PICKUP) {
            throw AppException.badRequest("Stop is not a pickup stop");
        }
        if (isTerminalStopStatus(pickupStop.getStatus())) {
            return toResponse(route); // idempotent
        }

        LocalDateTime now = LocalDateTime.now();
        if (route.getStatus() == RouteStatus.VALIDATED) {
            route.setStatus(RouteStatus.IN_PROGRESS);
            route.setStartedAt(now);
            routeRepository.save(route);
        }

        UUID depotId = pickupStop.getSourceDepotId();
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        int loaded = 0;
        for (RouteStop s : stops) {
            if (s.getStopType() == RouteStopType.PICKUP || s.getDeliveryId() == null) continue;
            Delivery delivery = deliveryRepository.findById(s.getDeliveryId()).orElse(null);
            if (delivery == null || !Objects.equals(delivery.getSourceDepotId(), depotId)) continue;
            if (delivery.getStatus() == DeliveryStatus.SCHEDULED) {
                delivery.setStatus(DeliveryStatus.PICKED_UP);
                delivery.setPickedUpAt(now);
                delivery.setAssignSlaMinutes(delayCalculationService.calculateAssignSlaMinutes(delivery));
                deliveryRepository.save(delivery);
                appendHistory(delivery, DeliveryStatus.PICKED_UP, driverId.toString(), Role.DRIVER,
                        "DEPOT_PICKUP_CONFIRMED", Map.of("driverId", driverId.toString(),
                                "depotId", depotId != null ? depotId.toString() : ""));
                syncStopFromDelivery(delivery.getId(), delivery.getStatus(), now, "Picked up at depot");
                loaded++;
            }
        }

        pickupStop.setStatus(RouteStopStatus.COMPLETED);
        pickupStop.setCompletedAt(now);
        routeStopRepository.save(pickupStop);

        String driverName = (principal != null && principal.getDisplayName() != null) ? principal.getDisplayName() : driverId.toString().substring(0, 8);
        String depotName = depotId != null
                ? depotRepository.findById(depotId).map(Depot::getName).orElse(null)
                : null;
        auditLogService.logAction(principal, "CONFIRM_PICKUP", "ROUTE", routeId.toString(),
                java.util.Map.of("chauffeur", driverName, "tournee", route.getName() != null ? route.getName() : routeId.toString(),
                        "depot", depotName != null ? depotName : (pickupStop.getSourceDepotId() != null ? pickupStop.getSourceDepotId().toString() : ""),
                        "colis", loaded, "action", "Chargement confirme au depot"));

        routeWebSocketService.notifyPickupConfirmed(route.getId(), route.getName(), driverId,
                driverName, depotName, loaded, now);

        return toResponse(route);
    }

    private void appendHistory(Delivery delivery, DeliveryStatus status, String changedBy, Role role, String eventKey, Map<String, Object> params) {
        String jsonParams = "{}";
        try {
            jsonParams = objectMapper.writeValueAsString(params != null ? params : Map.of());
        } catch (Exception ignored) {}
        
        deliveryStatusHistoryRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(delivery.getId())
                .status(status)
                .changedBy(changedBy)
                .changedByRole(role)
                .eventKey(eventKey)
                .eventParams(jsonParams)
                .changedAt(LocalDateTime.now())
                .build());
    }

    private RouteStopStatus resolveStopStatus(RouteStop stop, Delivery delivery) {
        RouteStopStatus current = stop.getStatus();
        if (current == RouteStopStatus.PENDING) {
            RouteStopStatus fallback = mapDeliveryToRouteStopStatus(delivery.getStatus());
            return fallback != null ? fallback : current;
        }
        return current;
    }

    @Transactional
    public void syncStopFromDelivery(UUID deliveryId, DeliveryStatus deliveryStatus, LocalDateTime eventAt, String note) {
        routeStopRepository.findByDeliveryIdWithRoute(deliveryId).ifPresent(stop -> {
            RouteStopStatus mappedStatus = mapDeliveryToRouteStopStatus(deliveryStatus);
            if (mappedStatus == null) {
                return;
            }
            if (!isStatusAdvance(stop.getStatus(), mappedStatus)) {
                return; // never move a stop backward
            }

            Route route = stop.getRoute();

            stop.setStatus(mappedStatus);
            if (isTerminalStopStatus(mappedStatus)) {
                stop.setCompletedAt(eventAt != null ? eventAt : LocalDateTime.now());
                stop.setActualDwellMinutes(delayCalculationService.calculateStopDurationMinutes(stop));
                stop.setCompletionStatus(delayCalculationService.calculateCompletionStatus(stop, route));
            }
            if (StringUtils.hasText(note)) {
                stop.setNotes(note.trim());
            }
            routeStopRepository.save(stop);
            if (route == null) {
                return;
            }

            if (route.getStatus() == RouteStatus.VALIDATED) {
                route.setStatus(RouteStatus.IN_PROGRESS);
                routeRepository.save(route);
            }

            List<RouteStop> routeStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
            Integer cumulativeDelayMinutes = delayCalculationService.calculateCumulativeDelayMinutes(route, routeStops);
            Double onTimeCompletionRate = delayCalculationService.calculateOnTimeCompletionRate(route, routeStops);
            route.setCumulativeDelayMinutes(cumulativeDelayMinutes);
            route.setRouteOnTimeCompletionRate(java.math.BigDecimal.valueOf(onTimeCompletionRate));
            routeRepository.save(route);

            routeAutoCloseService.finalizeIfResolved(route);
        });
    }


    private Route getRoute(UUID id) {
        return routeRepository.findById(id).orElseThrow(() -> AppException.notFound("Route not found"));
    }

    private static void ensureDraft(Route route) {
        if (route.getStatus() != RouteStatus.DRAFT) {
            throw AppException.badRequest("Only draft routes can be modified");
        }
    }

    private static void ensureDriverOwnsRoute(Route route, UUID driverId) {
        if (!route.getDriverId().equals(driverId)) {
            throw AppException.forbidden("Route is not assigned to this driver");
        }
    }

    private static final java.util.Map<RouteStopStatus, Integer> STOP_STATUS_ORDER = java.util.Map.of(
            RouteStopStatus.PENDING, 0,
            RouteStopStatus.SCHEDULED, 1,
            RouteStopStatus.PICKED_UP, 2,
            RouteStopStatus.IN_TRANSIT, 3,
            RouteStopStatus.ARRIVED, 4,
            RouteStopStatus.COMPLETED, 5,
            RouteStopStatus.FAILED, 5,
            RouteStopStatus.PARTIAL, 5
    );

    private static boolean isStatusAdvance(RouteStopStatus current, RouteStopStatus next) {
        int currentOrd = STOP_STATUS_ORDER.getOrDefault(current, -1);
        int nextOrd = STOP_STATUS_ORDER.getOrDefault(next, -1);
        return nextOrd > currentOrd;
    }

    private static RouteStopStatus mapDeliveryToRouteStopStatus(DeliveryStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case SCHEDULED -> RouteStopStatus.SCHEDULED;
            case PICKED_UP -> RouteStopStatus.PICKED_UP;
            case IN_TRANSIT -> RouteStopStatus.IN_TRANSIT;
            case DELIVERED -> RouteStopStatus.COMPLETED;
            case PARTIALLY_DELIVERED -> RouteStopStatus.PARTIAL;
            case FAILED -> RouteStopStatus.FAILED;
            default -> null;
        };
    }

    private static boolean isTerminalStopStatus(RouteStopStatus status) {
        return status == RouteStopStatus.COMPLETED
                || status == RouteStopStatus.FAILED
                || status == RouteStopStatus.PARTIAL;
    }

    private static boolean isRemovedStatus(RouteStopStatus status) {
        return status == RouteStopStatus.REMOVED_REPLANNED
                || status == RouteStopStatus.REMOVED_CANCELLED;
    }

    private RouteResponse toResponse(Route route) {
        return routePlanningService.get(route.getId());
    }

}
