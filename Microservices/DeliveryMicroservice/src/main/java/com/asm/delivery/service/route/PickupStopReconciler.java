package com.asm.delivery.service.route;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.RouteStopType;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteStopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Multi-depot routing core: keeps a route's PICKUP stops in sync with the depots its DELIVERY stops
 * actually need, orders each pickup before the deliveries it loads, and enforces that no delivery is
 * sequenced ahead of its depot load. The single owner of the "what loads where, in what order"
 * invariant — every stop-mutating path (route builder, reassign, replan, cancel) calls {@link
 * #reconcile(Route)} so the invariant can never be left broken.
 *
 * <p>Two strategies, by lifecycle:
 * <ul>
 *   <li><b>Planning</b> (DRAFT/VALIDATED) — nothing has been actioned yet, so pickups can be freely
 *       added / hard-removed and the whole sequence re-ordered.</li>
 *   <li><b>Execution</b> (IN_PROGRESS) — the driver is on the road; the actioned prefix is frozen.
 *       Orphaned pickups are <i>soft-deleted</i> (audit-preserving) and only while still PENDING; a
 *       newly-needed depot gets a fresh PENDING pickup (a COMPLETED pickup does <i>not</i> cover a box
 *       added after that depot was already loaded — it needs a return trip); only the pending tail is
 *       re-sequenced, never a stop the driver has started or finished.</li>
 * </ul>
 * CLOSED / CANCELLED routes are immutable and left untouched.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PickupStopReconciler {

    private final RouteStopRepository routeStopRepository;
    private final DeliveryRepository deliveryRepository;

    /** Reconcile a route's PICKUP stops against the depots its deliveries need, per lifecycle stage. */
    public void reconcile(Route route) {
        switch (route.getStatus()) {
            case DRAFT, VALIDATED -> reconcilePlanning(route);
            case IN_PROGRESS      -> reconcileExecution(route);
            default               -> { /* CLOSED / CANCELLED — immutable */ }
        }
    }

    // ── Planning (pre-departure): free add / hard-remove / reorder ───────────────

    private void reconcilePlanning(Route route) {
        RouteStops stops = loadActiveStops(route);
        // Pre-departure: home-depot parcels are loaded at route start, so only REMOTE depots need a pickup.
        Set<UUID> needed = neededDepots(route, stops.deliveries(), false);

        // Drop pickups no longer needed (hard delete — nothing has run yet).
        for (RouteStop pickup : stops.pickups()) {
            if (!needed.contains(pickup.getSourceDepotId())) {
                routeStopRepository.delete(pickup);
            }
        }
        // Add pickups for newly-needed depots.
        Set<UUID> existing = stops.pickups().stream().map(RouteStop::getSourceDepotId).collect(Collectors.toSet());
        for (UUID depotId : needed) {
            if (!existing.contains(depotId)) {
                routeStopRepository.save(newPickup(route, depotId));
            }
        }
        normalizeStopOrder(route);
    }

    // ── Execution (on the road): frozen prefix, soft-delete, tail-only reorder ──

    private void reconcileExecution(Route route) {
        RouteStops stops = loadActiveStops(route);
        // After departure (IN_PROGRESS ⟹ start-load already ran): a not-yet-loaded parcel for ANY depot —
        // home included — needs a load stop. The start-load moment is gone, so a home-depot parcel added
        // now (e.g. a reassign onto a route that already left its depot) requires a return trip, exactly
        // like a remote one. Parcels loaded at start are PICKED_UP (pickedUpAt set) and stay excluded, so
        // this never conjures a spurious home pickup.
        Set<UUID> needed = neededDepots(route, stops.deliveries(), true);

        // Depots already covered by a still-PENDING pickup. A COMPLETED pickup does NOT count: a box
        // added to that depot after the driver already loaded there needs a fresh return-trip pickup.
        Set<UUID> coveredByPending = stops.pickups().stream()
                .filter(p -> p.getStatus() == RouteStopStatus.PENDING)
                .map(RouteStop::getSourceDepotId)
                .collect(Collectors.toSet());

        // Drop orphaned pickups (soft-delete — keep the audit row), but only while still PENDING.
        // A COMPLETED pickup is part of the frozen past: the driver really loaded there, leave it.
        for (RouteStop pickup : stops.pickups()) {
            if (pickup.getStatus() == RouteStopStatus.PENDING && !needed.contains(pickup.getSourceDepotId())) {
                softDeleteOrphan(pickup);
                routeStopRepository.save(pickup);
            }
        }
        // Add a PENDING pickup for every needed depot not already covered by a pending one.
        for (UUID depotId : needed) {
            if (!coveredByPending.contains(depotId)) {
                routeStopRepository.save(newPickup(route, depotId));
            }
        }
        renumberExecutionTail(route);
    }

    // ── Shared: which depots the route's not-yet-loaded deliveries require a load stop for ──

    /**
     * Depots that need a PICKUP stop for the route's not-yet-loaded parcels.
     * @param afterDeparture when false (planning) home-depot parcels are excluded — they load at route
     *        start; when true (execution) the home depot is included too, because a not-yet-loaded parcel
     *        added after departure needs a return trip to its depot just like a remote one.
     */
    private Set<UUID> neededDepots(Route route, List<RouteStop> deliveryStops, boolean afterDeparture) {
        List<UUID> ids = deliveryStops.stream().map(RouteStop::getDeliveryId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Set.of();
        return deliveryRepository.findAllByIdInWithOrder(ids).stream()
                .filter(d -> (d.getStatus() == DeliveryStatus.UNSCHEDULED || d.getStatus() == DeliveryStatus.SCHEDULED)
                        // pickedUpAt != null ⇒ the parcel is already in a driver's hands. An in-field
                        // reassign downgrades it to SCHEDULED but keeps pickedUpAt, and it changes hands
                        // by driver-to-driver handoff, not a depot load — so it must NOT pull a PICKUP.
                        && d.getPickedUpAt() == null)
                .flatMap(d -> depotsOf(d).stream())
                // Home depot only needs a pickup once the route has departed (start-load is gone).
                .filter(depotId -> afterDeparture || !depotId.equals(route.getDepotId()))
                .collect(Collectors.toSet());
    }

    // ── Ordering ────────────────────────────────────────────────────────────────

    /** Renumber all active stops so each remote-depot pickup precedes the deliveries it loads. */
    public void normalizeStopOrder(Route route) {
        RouteStops stops = loadActiveStops(route);
        List<RouteStop> ordered = groupPickupsBeforeDeliveries(
                stops.deliveries(), stops.pickups(), depotsByDelivery(stops.deliveries()));
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setStopOrder(i + 1);
        }
        routeStopRepository.saveAll(ordered);
    }

    /**
     * Execution renumber: keep the actioned prefix exactly where it is and re-sequence only the pending
     * tail — each depot's pending pickup grouped immediately before its first pending delivery. Never
     * reshuffles a stop the driver has already started or finished.
     */
    private void renumberExecutionTail(Route route) {
        RouteStops stops = loadActiveStops(route);
        List<RouteStop> settled = stops.all().stream().filter(s -> !isReorderable(s)).toList();
        List<RouteStop> tailDeliveries = stops.deliveries().stream().filter(PickupStopReconciler::isReorderable).toList();
        List<RouteStop> tailPickups = stops.pickups().stream().filter(PickupStopReconciler::isReorderable).toList();

        List<RouteStop> orderedTail = groupPickupsBeforeDeliveries(
                tailDeliveries, tailPickups, depotsByDelivery(tailDeliveries));

        int order = settled.stream().mapToInt(RouteStop::getStopOrder).max().orElse(0);
        for (RouteStop stop : orderedTail) {
            stop.setStopOrder(++order);
        }
        routeStopRepository.saveAll(orderedTail);
    }

    /** Reject an ordering where a delivery is sequenced before its depot's load stop. */
    public void assertPickupPrecedence(List<RouteStop> orderedStops) {
        Map<UUID, Integer> pickupOrder = new HashMap<>();
        List<UUID> deliveryIds = orderedStops.stream()
                .filter(s -> s.getStopType() == RouteStopType.DELIVERY && s.getDeliveryId() != null)
                .map(RouteStop::getDeliveryId)
                .toList();
        Map<UUID, Set<UUID>> deliveryToDepots = new HashMap<>();
        if (!deliveryIds.isEmpty()) {
            for (Delivery d : deliveryRepository.findAllByIdInWithOrder(deliveryIds)) {
                Set<UUID> depots = depotsOf(d);
                if (!depots.isEmpty()) deliveryToDepots.put(d.getId(), depots);
            }
        }

        for (RouteStop stop : orderedStops) {
            if (stop.getStopType() == RouteStopType.PICKUP && stop.getSourceDepotId() != null) {
                pickupOrder.put(stop.getSourceDepotId(), stop.getStopOrder());
            }
        }

        for (RouteStop stop : orderedStops) {
            if (stop.getStopType() == RouteStopType.DELIVERY && stop.getDeliveryId() != null) {
                for (UUID depotId : deliveryToDepots.getOrDefault(stop.getDeliveryId(), Set.of())) {
                    Integer loadedAt = pickupOrder.get(depotId);
                    if (loadedAt != null && loadedAt > stop.getStopOrder()) {
                        throw AppException.badRequest("La livraison ne peut pas être planifiée avant le chargement de son dépôt");
                    }
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /** Pure ordering: emit each depot's pickup before the first delivery that needs it, then any
     *  pickup whose deliveries all fell away. A parcel drawn from two depots waits for both. */
    private static List<RouteStop> groupPickupsBeforeDeliveries(List<RouteStop> deliveryStops,
                                                                List<RouteStop> pickupStops,
                                                                Map<UUID, Set<UUID>> deliveryToDepots) {
        Map<UUID, RouteStop> pickupByDepot = pickupStops.stream()
                .collect(Collectors.toMap(RouteStop::getSourceDepotId, Function.identity(), (a, b) -> a));
        Set<UUID> emitted = new HashSet<>();
        List<RouteStop> ordered = new ArrayList<>();

        for (RouteStop delivery : deliveryStops) {
            for (UUID depotId : deliveryToDepots.getOrDefault(delivery.getDeliveryId(), Set.of())) {
                if (pickupByDepot.containsKey(depotId) && emitted.add(depotId)) {
                    ordered.add(pickupByDepot.get(depotId));
                }
            }
            ordered.add(delivery);
        }
        for (RouteStop pickup : pickupStops) {
            if (!emitted.contains(pickup.getSourceDepotId())) {
                ordered.add(pickup);
            }
        }
        return ordered;
    }

    private Map<UUID, Set<UUID>> depotsByDelivery(List<RouteStop> deliveryStops) {
        List<UUID> ids = deliveryStops.stream().map(RouteStop::getDeliveryId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        Map<UUID, Set<UUID>> out = new HashMap<>();
        for (Delivery d : deliveryRepository.findAllByIdInWithOrder(ids)) {
            Set<UUID> depots = depotsOf(d);
            if (!depots.isEmpty()) out.put(d.getId(), depots);
        }
        return out;
    }

    /**
     * Every depot this parcel must be loaded from.
     *
     * <p>A shipment used to have one, and the field on the order still says so. It holds wherever an
     * ERP issues a delivery note per warehouse — Odoo does. ERPNext puts a warehouse on each line, so
     * one note can legitimately need two loads, and answering with the header alone sent a driver to
     * fetch in Sousse goods that sit in Monastir.
     *
     * <p>Lines win when they carry a depot; the order's own depot answers for everything imported
     * before they did, which is what this method used to return outright.
     */
    private static Set<UUID> depotsOf(Delivery delivery) {
        Order order = delivery.getOrder();
        if (order != null && order.getItems() != null) {
            Set<UUID> fromLines = order.getItems().stream()
                    .map(com.asm.delivery.entity.OrderItem::getSourceDepotId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!fromLines.isEmpty()) return fromLines;
        }
        return delivery.getSourceDepotId() != null ? Set.of(delivery.getSourceDepotId()) : Set.of();
    }

    private RouteStops loadActiveStops(Route route) {
        List<RouteStop> active = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId()).stream()
                .filter(s -> !RoutePlanningService.isRemovedStatus(s.getStatus()))
                .toList();
        return new RouteStops(
                active.stream().filter(s -> s.getStopType() == RouteStopType.DELIVERY).toList(),
                active.stream().filter(s -> s.getStopType() == RouteStopType.PICKUP).toList(),
                active);
    }

    private static RouteStop newPickup(Route route, UUID depotId) {
        return RouteStop.builder()
                .route(route)
                .stopType(RouteStopType.PICKUP)
                .sourceDepotId(depotId)
                .deliveryId(null)
                .stopOrder(0)
                .status(RouteStopStatus.PENDING)
                .build();
    }

    private static void softDeleteOrphan(RouteStop pickup) {
        pickup.setStatus(RouteStopStatus.REMOVED_REPLANNED);
        pickup.setRemovedAt(LocalDateTime.now());
        pickup.setRemovedReason("PICKUP_ORPHANED");
        pickup.setRemovedBy(com.asm.delivery.web.ActorContext.changedBy());
    }

    /** Only not-yet-started stops (PENDING / SCHEDULED) may be re-sequenced during execution. */
    private static boolean isReorderable(RouteStop stop) {
        return stop.getStatus() == RouteStopStatus.PENDING || stop.getStatus() == RouteStopStatus.SCHEDULED;
    }

    private record RouteStops(List<RouteStop> deliveries, List<RouteStop> pickups, List<RouteStop> all) {}
}
