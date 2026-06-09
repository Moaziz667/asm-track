package com.asm.delivery.service.route;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.RouteStopType;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteStopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Multi-depot routing core: keeps a route's PICKUP stops in sync with the depots its DELIVERY stops
 * actually need, orders pickups before their deliveries, and enforces that no delivery is sequenced
 * ahead of its depot load. Extracted from RoutePlanningService so the "what loads where, in what
 * order" concern lives in one cohesive place.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PickupStopReconciler {

    private final RouteStopRepository routeStopRepository;
    private final DeliveryRepository deliveryRepository;

    /**
     * Ensure the route carries exactly the PICKUP stops its remote-depot deliveries require:
     * drop pickups no longer needed, add pickups for newly-needed depots, then renumber.
     * No-op once the route has left planning (DRAFT/VALIDATED).
     */
    public void reconcile(Route route) {
        if (route.getStatus() != RouteStatus.DRAFT && route.getStatus() != RouteStatus.VALIDATED) {
            return;
        }

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        List<RouteStop> deliveryStops = stops.stream()
                .filter(s -> !RoutePlanningService.isRemovedStatus(s.getStatus()) && s.getStopType() == RouteStopType.DELIVERY)
                .toList();
        List<RouteStop> pickupStops = stops.stream()
                .filter(s -> !RoutePlanningService.isRemovedStatus(s.getStatus()) && s.getStopType() == RouteStopType.PICKUP)
                .toList();

        List<UUID> deliveryIds = deliveryStops.stream().map(RouteStop::getDeliveryId).toList();
        List<Delivery> deliveries = deliveryIds.isEmpty() ? List.of() : deliveryRepository.findAllByIdInWithOrder(deliveryIds);

        Set<UUID> neededDepots = deliveries.stream()
                .filter(d -> d.getSourceDepotId() != null
                        && !d.getSourceDepotId().equals(route.getDepotId())
                        && (d.getStatus() == DeliveryStatus.UNSCHEDULED || d.getStatus() == DeliveryStatus.SCHEDULED))
                .map(Delivery::getSourceDepotId)
                .collect(Collectors.toSet());

        // Remove unneeded pickups
        for (RouteStop pickup : pickupStops) {
            if (!neededDepots.contains(pickup.getSourceDepotId())) {
                routeStopRepository.delete(pickup);
            }
        }

        // Add missing pickups
        Set<UUID> existingPickups = pickupStops.stream().map(RouteStop::getSourceDepotId).collect(Collectors.toSet());
        for (UUID depotId : neededDepots) {
            if (!existingPickups.contains(depotId)) {
                RouteStop newPickup = RouteStop.builder()
                        .route(route)
                        .stopType(RouteStopType.PICKUP)
                        .sourceDepotId(depotId)
                        .deliveryId(null)
                        .stopOrder(0)
                        .status(RouteStopStatus.PENDING)
                        .build();
                routeStopRepository.save(newPickup);
            }
        }

        normalizeStopOrder(route);
    }

    /** Renumber active stops so each remote-depot pickup precedes the deliveries it loads. */
    public void normalizeStopOrder(Route route) {
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        List<RouteStop> activeStops = stops.stream().filter(s -> !RoutePlanningService.isRemovedStatus(s.getStatus())).toList();

        List<RouteStop> deliveryStops = activeStops.stream().filter(s -> s.getStopType() == RouteStopType.DELIVERY).toList();
        List<RouteStop> pickupStops = activeStops.stream().filter(s -> s.getStopType() == RouteStopType.PICKUP).toList();

        List<UUID> deliveryIds = deliveryStops.stream().map(RouteStop::getDeliveryId).toList();
        Map<UUID, UUID> deliveryToDepot = deliveryIds.isEmpty() ? Map.of() : deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                .filter(d -> d.getSourceDepotId() != null)
                .collect(Collectors.toMap(Delivery::getId, Delivery::getSourceDepotId));

        Map<UUID, RouteStop> pickupByDepot = pickupStops.stream().collect(Collectors.toMap(RouteStop::getSourceDepotId, Function.identity()));
        Set<UUID> emittedPickups = new HashSet<>();

        List<RouteStop> ordered = new ArrayList<>();

        for (RouteStop deliveryStop : deliveryStops) {
            UUID depotId = deliveryToDepot.get(deliveryStop.getDeliveryId());
            if (depotId != null && pickupByDepot.containsKey(depotId) && !emittedPickups.contains(depotId)) {
                ordered.add(pickupByDepot.get(depotId));
                emittedPickups.add(depotId);
            }
            ordered.add(deliveryStop);
        }

        for (RouteStop pickup : pickupStops) {
            if (!emittedPickups.contains(pickup.getSourceDepotId())) {
                ordered.add(pickup);
            }
        }

        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setStopOrder(i + 1);
        }
        routeStopRepository.saveAll(ordered);
    }

    /** Reject an ordering where a delivery is sequenced before its depot's load stop. */
    public void assertPickupPrecedence(List<RouteStop> orderedStops) {
        Map<UUID, Integer> pickupOrder = new HashMap<>();
        List<UUID> deliveryIds = orderedStops.stream()
                .filter(s -> s.getStopType() == RouteStopType.DELIVERY && s.getDeliveryId() != null)
                .map(RouteStop::getDeliveryId)
                .toList();
        Map<UUID, UUID> deliveryToDepot = deliveryIds.isEmpty() ? Map.of() : deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                .filter(d -> d.getSourceDepotId() != null)
                .collect(Collectors.toMap(Delivery::getId, Delivery::getSourceDepotId));

        for (RouteStop stop : orderedStops) {
            if (stop.getStopType() == RouteStopType.PICKUP && stop.getSourceDepotId() != null) {
                pickupOrder.put(stop.getSourceDepotId(), stop.getStopOrder());
            }
        }

        for (RouteStop stop : orderedStops) {
            if (stop.getStopType() == RouteStopType.DELIVERY && stop.getDeliveryId() != null) {
                UUID depotId = deliveryToDepot.get(stop.getDeliveryId());
                if (depotId != null && pickupOrder.containsKey(depotId)) {
                    if (pickupOrder.get(depotId) > stop.getStopOrder()) {
                        throw AppException.badRequest("La livraison ne peut pas être planifiée avant le chargement de son dépôt");
                    }
                }
            }
        }
    }
}
