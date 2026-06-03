package com.asm.delivery.service;

import com.asm.delivery.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SlaMonitoringService {

    private final com.asm.delivery.repository.DeliveryRepository deliveryRepository;
    private final com.asm.delivery.repository.RouteStopRepository routeStopRepository;
    private final com.asm.delivery.repository.DepotRepository depotRepository;
    private final EventPublisher eventPublisher;
    private final SystemSettingsService settings;
    private final java.util.Set<String> alertedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${app.sla.check-interval-ms:20000}")
    @Transactional
    public void checkSlaStatuses() {
        processSla();
    }

    /**
     * Clear a delivery's SLA alert keys so it can re-alert in a new lifecycle (e.g. after a replan,
     * the delivery returns to UNSCHEDULED with a new scheduled date and must be eligible again).
     */
    public void clearDeliveryAlerts(UUID deliveryId) {
        if (deliveryId == null) return;
        String id = deliveryId.toString();
        alertedKeys.remove(id + ":WAITING");
        alertedKeys.remove(id + ":ASSIGNMENT");
        alertedKeys.remove(id + ":PICKUP");
        alertedKeys.remove(id + ":TRANSIT");
    }

    private void processSla() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        
        // Fetch company-specific limits
        int waitingLimit = settings.getInt("ops.sla.waiting-limit-minutes", 15);
        int assignLeadTime = settings.getInt("ops.sla.assign-leadtime-minutes", 120);
        int assignLimit = settings.getInt("ops.sla.assign-limit-minutes", 20);
        int pickupLimit = settings.getInt("ops.sla.pickup-limit-minutes", 15);

        // 1. Assignment lead-time SLA: an order must be assigned to a driver at least
        //    `assignLeadTime` minutes BEFORE its ERP scheduled date. Anchoring on the
        //    commitment (scheduledAt) avoids false breaches on orders created far in advance.
        //    Fallback to legacy "since creation" only when the order has no scheduled date.
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.UNSCHEDULED).forEach(d -> {
            java.time.LocalDateTime scheduledAt = d.getOrder() != null ? d.getOrder().effectiveScheduledAt() : null;
            boolean breached;
            long overdueMinutes;
            if (scheduledAt != null) {
                // Breach once we're within `assignLeadTime` of the commitment (or past it) and the
                // order is still unassigned. alertedKeys dedups so each order alerts only once,
                // even if it was just rescheduled to a near/overdue time (still worth surfacing).
                java.time.LocalDateTime deadline = scheduledAt.minusMinutes(assignLeadTime);
                breached = now.isAfter(deadline);
                overdueMinutes = java.time.Duration.between(deadline, now).toMinutes();
            } else {
                long elapsedMinutes = java.time.Duration.between(d.getCreatedAt(), now).toMinutes();
                breached = elapsedMinutes > waitingLimit;
                overdueMinutes = elapsedMinutes;
            }
            if (breached && alertedKeys.add(d.getId() + ":WAITING")) {
                eventPublisher.publishSlaBreach(d, "SLA_WAITING", "WARNING",
                    java.util.Map.of(
                        "elapsed", Math.max(0, overdueMinutes),
                        "limit", scheduledAt != null ? assignLeadTime : waitingLimit,
                        "scheduledAt", scheduledAt != null ? scheduledAt.toString() : ""));
            }
        });

        // 2. Assignment SLA (Délai de Démarrage) — multi-depot guard:
        //    for deliveries from a secondary depot, the clock starts when the
        //    driver actually reaches that depot (pickup completed or ETA), not
        //    when the delivery was assigned hours earlier.
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.SCHEDULED).forEach(d -> {
            java.time.LocalDateTime baseline = d.getAssignedAt();
            if (baseline == null) baseline = d.getCreatedAt();

            // Multi-depot: adjust baseline for secondary depot deliveries
            var stopOpt = routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId());
            if (stopOpt.isPresent() && stopOpt.get().getRoute() != null
                    && d.getSourceDepotId() != null
                    && !d.getSourceDepotId().equals(stopOpt.get().getRoute().getDepotId())) {
                UUID routeId = stopOpt.get().getRoute().getId();
                java.util.List<com.asm.delivery.entity.RouteStop> allStops =
                        routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
                com.asm.delivery.entity.RouteStop pickupStop = allStops.stream()
                        .filter(s -> s.getStopType() == com.asm.delivery.entity.RouteStopType.PICKUP
                                && d.getSourceDepotId().equals(s.getSourceDepotId()))
                        .findFirst().orElse(null);
                if (pickupStop != null) {
                    if (pickupStop.getCompletedAt() != null) {
                        baseline = pickupStop.getCompletedAt();
                    } else if (pickupStop.getEtaAt() != null && pickupStop.getEtaAt().isAfter(baseline)) {
                        baseline = pickupStop.getEtaAt();
                    } else {
                        return; // driver hasn't reached the secondary depot yet → no alert
                    }
                }
            }

            long elapsedSeconds = java.time.Duration.between(baseline, now).getSeconds();
            if (elapsedSeconds > (assignLimit * 60L)) {
                if (alertedKeys.add(d.getId() + ":ASSIGNMENT")) {
                    eventPublisher.publishSlaBreach(d, "SLA_ASSIGNMENT", "CRITICAL", 
                        java.util.Map.of("elapsed", elapsedSeconds / 60, "limit", assignLimit));
                }
            }
        });

        // 3. Pickup SLA (Délai de Départ)
        // Multi-depot guard: a delivery is PICKED_UP as soon as its depot is confirmed, but the
        // driver may still be loading OTHER depots — so the "departure" clock must not run while
        // the route still has pending PICKUP stops, and it starts at the LAST loading completion.
        java.util.Map<UUID, PickupPhase> pickupPhaseByRoute = new java.util.HashMap<>();
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.PICKED_UP).forEach(d -> {
            java.time.LocalDateTime baseline = d.getPickedUpAt();
            if (baseline == null) baseline = d.getAssignedAt();
            if (baseline == null) baseline = d.getCreatedAt();

            var stopOpt = routeStopRepository.findActiveByDeliveryIdWithRoute(d.getId());
            if (stopOpt.isPresent() && stopOpt.get().getRoute() != null) {
                UUID routeId = stopOpt.get().getRoute().getId();
                PickupPhase phase = pickupPhaseByRoute.computeIfAbsent(routeId, this::analyzePickups);
                if (phase.hasPickups()) {
                    if (phase.stillLoading()) return;                    // still loading other depots — no departure alert
                    if (phase.lastPickupAt() != null) baseline = phase.lastPickupAt();  // clock starts when loading completed
                }
            }

            long elapsedSeconds = java.time.Duration.between(baseline, now).getSeconds();
            if (elapsedSeconds > (pickupLimit * 60L)) {
                if (alertedKeys.add(d.getId() + ":PICKUP")) {
                    eventPublisher.publishSlaBreach(d, "SLA_PICKUP", "CRITICAL",
                        java.util.Map.of("elapsed", elapsedSeconds / 60, "limit", pickupLimit));
                }
            }
        });

        // 4. Transit SLA
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.IN_TRANSIT).forEach(d -> {
            routeStopRepository.findByDeliveryId(d.getId()).ifPresent(stop -> {
                if (stop.getEndTimeWindow() != null) {
                    com.asm.delivery.entity.Route route = stop.getRoute();
                    java.time.LocalDate refDate = route != null ? route.getDate() : now.toLocalDate();
                    java.time.LocalDateTime deadline = refDate.atTime(stop.getEndTimeWindow());
                    if (now.isAfter(deadline)) {
                        if (alertedKeys.add(d.getId() + ":TRANSIT")) {
                            eventPublisher.publishSlaBreach(d, "SLA_TRANSIT", "CRITICAL",
                                java.util.Map.of("deadline", stop.getEndTimeWindow().toString()));
                        }
                    }
                }
            });
        });

        // 5. Depot pickup overdue (multi-depot): a PICKUP stop on an in-progress route not yet
        //    confirmed past the threshold after route start → alert driver + dispatcher.
        int depotPickupOverdue = settings.getInt("ops.sla.depot-pickup-overdue-minutes", 30);
        routeStopRepository.findActivePendingStops().stream()
                .filter(s -> s.getStopType() == com.asm.delivery.entity.RouteStopType.PICKUP)
                .forEach(s -> {
                    com.asm.delivery.entity.Route route = s.getRoute();
                    if (route == null
                            || route.getStatus() != com.asm.delivery.entity.RouteStatus.IN_PROGRESS
                            || route.getStartedAt() == null) {
                        return;
                    }
                    // Anchor on the stop's ETA when the optimizer computed one (so a 2nd-depot
                    // pickup isn't flagged before the driver could reach it);
                    // fallback: previous completed stop + estimated drive time.
                    java.time.LocalDateTime anchor = s.getEtaAt();
                    if (anchor == null) {
                        com.asm.delivery.entity.RouteStop prev = findPreviousActiveStop(s, route.getId());
                        if (prev != null && prev.getCompletedAt() != null) {
                            long driveSec = s.getDriveDurationSeconds() != null ? s.getDriveDurationSeconds() : 0;
                            anchor = prev.getCompletedAt().plusSeconds(driveSec);
                        } else {
                            anchor = route.getStartedAt();
                        }
                    }
                    long mins = java.time.Duration.between(anchor, now).toMinutes();
                    if (mins > depotPickupOverdue && alertedKeys.add(s.getId() + ":PICKUP_OVERDUE")) {
                        String depotName = s.getSourceDepotId() != null
                                ? depotRepository.findById(s.getSourceDepotId())
                                    .map(com.asm.delivery.entity.Depot::getName).orElse(null)
                                : null;
                        int parcels = (int) routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId()).stream()
                                .filter(d -> d.getStopType() != com.asm.delivery.entity.RouteStopType.PICKUP
                                        && d.getSourceDepotId() != null
                                        && d.getSourceDepotId().equals(s.getSourceDepotId()))
                                .count();
                        eventPublisher.publishPickupOverdue(route, s, depotName, parcels);
                    }
                });
    }

    /** Pickup phase of a route: whether it has PICKUP stops, if any are still pending, and when loading finished. */
    private record PickupPhase(boolean hasPickups, boolean stillLoading, java.time.LocalDateTime lastPickupAt) {}

    /** Find the immediately preceding active (non-removed) stop for a given pickup. */
    private com.asm.delivery.entity.RouteStop findPreviousActiveStop(com.asm.delivery.entity.RouteStop pickup, UUID routeId) {
        return routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId).stream()
                .filter(s -> s.getStopOrder() < pickup.getStopOrder()
                        && s.getStatus() != com.asm.delivery.entity.RouteStopStatus.REMOVED_REPLANNED
                        && s.getStatus() != com.asm.delivery.entity.RouteStopStatus.REMOVED_CANCELLED)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private PickupPhase analyzePickups(UUID routeId) {
        java.util.List<com.asm.delivery.entity.RouteStop> pickups =
                routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId).stream()
                        .filter(s -> s.getStopType() == com.asm.delivery.entity.RouteStopType.PICKUP)
                        .toList();
        if (pickups.isEmpty()) return new PickupPhase(false, false, null);
        boolean stillLoading = pickups.stream()
                .anyMatch(p -> p.getStatus() != com.asm.delivery.entity.RouteStopStatus.COMPLETED);
        java.time.LocalDateTime last = pickups.stream()
                .map(com.asm.delivery.entity.RouteStop::getCompletedAt)
                .filter(java.util.Objects::nonNull)
                .max(java.time.LocalDateTime::compareTo)
                .orElse(null);
        return new PickupPhase(true, stillLoading, last);
    }
}
