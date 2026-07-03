package com.asm.delivery.service.route;

import com.asm.delivery.dto.response.RouteReportResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.storage.MinioStorageService;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes and persists immutable Route reports.
 *
 * Strategy: snapshot at close time (called from RouteExecutionService),
 * stored as JSON. If the snapshot is missing when load() is called (legacy
 * routes closed before this feature shipped), it is computed on the fly
 * and persisted so subsequent loads are instant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouteReportService {

    private final RouteReportRepository routeReportRepository;
    private final RouteStopRepository routeStopRepository;
    private final DeliveryRepository deliveryRepository;
    private final ProofOfDeliveryRepository podRepository;
    private final DeliveryStatusHistoryRepository statusHistoryRepository;
    private final VehicleRepository vehicleRepository;
    private final DepotRepository depotRepository;
    private final TransportPort transportPort;
    private final DelayCalculationService delayCalculationService;
    private final MinioStorageService minioStorageService;
    private final ObjectMapper objectMapper;
    private final com.asm.delivery.web.ActorNameResolver actorNameResolver;

    /** Compute the report payload from current entity state. No DB writes. */
    @Transactional(readOnly = true)
    public RouteReportResponse buildReport(Route route) {
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());

        // Resolve linked deliveries (join-fetched with order)
        List<UUID> deliveryIds = stops.stream().map(RouteStop::getDeliveryId).filter(Objects::nonNull).toList();
        Map<UUID, Delivery> deliveriesById = new HashMap<>();
        if (!deliveryIds.isEmpty()) {
            for (Delivery d : deliveryRepository.findAllByIdInWithOrder(deliveryIds)) {
                deliveriesById.put(d.getId(), d);
            }
        }

        // Driver / vehicle / depot resolution
        DriverDTO driver = route.getDriverId() != null
                ? safeGetDriver(route.getDriverId().toString()) : null;
        Vehicle vehicle = route.getVehicleId() != null
                ? vehicleRepository.findById(route.getVehicleId()).orElse(null) : null;
        Depot depot = route.getDepotId() != null
                ? depotRepository.findById(route.getDepotId()).orElse(null) : null;

        // Build sub-sections
        List<RouteReportResponse.StopRow> stopRows = buildStopRows(stops, deliveriesById);
        RouteReportResponse.Kpis kpis = computeKpis(route, stops, stopRows);
        List<RouteReportResponse.StatusBucket> breakdown = buildStatusBreakdown(stopRows);
        List<RouteReportResponse.TimelinePoint> timeline = buildTimeline(stops, stopRows, deliveriesById);
        List<RouteReportResponse.MovementEvent> movements = buildMovements(stops, stopRows, deliveriesById);
        List<RouteReportResponse.PodEntry> podGallery = buildPodGallery(stops, deliveriesById);
        List<RouteReportResponse.AuditEntry> auditTrail = buildAuditTrail(stops);

        return RouteReportResponse.builder()
                .header(buildHeader(route, driver, vehicle, depot))
                .kpis(kpis)
                .statusBreakdown(breakdown)
                .timeline(timeline)
                .stops(stopRows)
                .movements(movements)
                .geometry(route.getRouteGeometry())
                .podGallery(podGallery)
                .auditTrail(auditTrail)
                .generatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Persist a snapshot for this route. Idempotent: if a row exists, it is replaced.
     * Called from {@code RouteExecutionService} after a route transitions to CLOSED.
     */
    @Transactional
    public void persistSnapshot(Route route) {
        try {
            RouteReportResponse payload = buildReport(route);
            JsonNode json = objectMapper.valueToTree(payload);

            RouteReport existing = routeReportRepository.findByRouteId(route.getId()).orElse(null);
            if (existing != null) {
                existing.setPayload(json);
                existing.setGeneratedAt(LocalDateTime.now());
                routeReportRepository.save(existing);
            } else {
                routeReportRepository.save(RouteReport.builder()
                        .routeId(route.getId())
                        .payload(json)
                        .build());
            }
            log.info("RouteReport snapshot persisted route={} ",
                    route.getId(), null);
        } catch (Exception e) {
            // Never let a snapshot failure roll back the route close — log loudly.
            log.error("RouteReport snapshot FAILED route={} reason={}", route.getId(), e.getMessage(), e);
        }
    }

    /**
     * Read snapshot from DB. If missing, build on the fly and persist (legacy
     * routes that closed before this feature shipped).
     */
    @Transactional
    public RouteReportResponse load(Route route) {
        Optional<RouteReport> stored = routeReportRepository.findByRouteId(route.getId());
        if (stored.isPresent()) {
            try {
                return objectMapper.treeToValue(stored.get().getPayload(), RouteReportResponse.class);
            } catch (Exception e) {
                log.warn("RouteReport payload parse failed route={} — recomputing", route.getId(), e);
            }
        }
        // Recompute and persist
        RouteReportResponse payload = buildReport(route);
        try {
            JsonNode json = objectMapper.valueToTree(payload);
            routeReportRepository.save(RouteReport.builder()
                    .routeId(route.getId())
                    .payload(json)
                    .build());
        } catch (Exception e) {
            log.warn("RouteReport persist after recompute failed route={}: {}", route.getId(), e.getMessage());
        }
        return payload;
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private RouteReportResponse.Header buildHeader(Route route, DriverDTO driver, Vehicle vehicle, Depot depot) {
        Integer durationMinutes = null;
        if (route.getStartedAt() != null && route.getClosedAt() != null) {
            durationMinutes = (int) Duration.between(route.getStartedAt(), route.getClosedAt()).toMinutes();
        }
        return RouteReportResponse.Header.builder()
                .routeId(route.getId())
                .routeName(route.getName())
                .date(route.getDate())
                .driverName(driver != null ? driver.getName() : null)
                .vehiclePlate(vehicle != null ? vehicle.getPlate() : null)
                .vehicleType(vehicle != null ? vehicle.getName() : null)
                .depotName(depot != null ? depot.getName() : null)
                .status(route.getStatus() != null ? route.getStatus().name() : null)
                .startedAt(route.getStartedAt())
                .closedAt(route.getClosedAt())
                .durationMinutes(durationMinutes)
                .plannedStartTime(route.getPlannedStartTime())
                .plannedEndTime(route.getPlannedEndTime())
                .build();
    }

    private List<RouteReportResponse.StopRow> buildStopRows(
            List<RouteStop> stops, Map<UUID, Delivery> deliveriesById) {

        // Pre-fetch existence-only POD info (avoid heavy join, we just need a boolean)
        Set<UUID> withPod = new HashSet<>();
        for (RouteStop s : stops) {
            if (s.getDeliveryId() != null && podRepository.findByDeliveryId(s.getDeliveryId()).isPresent()) {
                withPod.add(s.getDeliveryId());
            }
        }

        return stops.stream().map(s -> {
            Delivery d = deliveriesById.get(s.getDeliveryId());
            Order order = d != null ? d.getOrder() : null;
            Route route = s.getRoute();

            Integer delay = delayCalculationService.calculateStrictStopDelayMinutes(s, route);
            String classification = classify(s, delay);
            String movement = resolveMovement(s);
            String movementTarget = resolveMovementTarget(s);

            String handoffFromName = null, handoffToName = null;
            if (s.getHandoffFromDriverId() != null) {
                DriverDTO from = safeGetDriver(s.getHandoffFromDriverId().toString());
                if (from != null) handoffFromName = from.getName();
            }
            if (s.getHandoffToDriverId() != null) {
                DriverDTO to = safeGetDriver(s.getHandoffToDriverId().toString());
                if (to != null) handoffToName = to.getName();
            }

            return RouteReportResponse.StopRow.builder()
                    .stopId(s.getId())
                    .deliveryId(s.getDeliveryId())
                    .stopOrder(s.getStopOrder())
                    .clientName(order != null ? order.getClientName() : null)
                    .address(order != null ? order.getDropoffAddress() : null)
                    .city(order != null ? order.getDropoffCity() : null)
                    .startTimeWindow(s.getStartTimeWindow())
                    .endTimeWindow(s.getEndTimeWindow())
                    .arrivedAt(s.getActualArrivalAt() != null ? s.getActualArrivalAt() : s.getArrivedAt())
                    .completedAt(s.getCompletedAt())
                    .delayMinutes(delay)
                    .dwellMinutes(s.getActualDwellMinutes())
                    .completionStatus(s.getCompletionStatus())
                    .finalStatus(s.getStatus() != null ? s.getStatus().name() : null)
                    .classification(classification)
                    .hasPod(s.getDeliveryId() != null && withPod.contains(s.getDeliveryId()))
                    .movement(movement)
                    .movementTarget(movementTarget)
                    .removedAt(s.getRemovedAt())
                    .removedReason(s.getRemovedReason())
                    .removedBy(s.getRemovedBy())
                    .handoffConfirmedAt(s.getHandoffConfirmedAt())
                    .handoffFromDriverName(handoffFromName)
                    .handoffToDriverName(handoffToName)
                    .failureCode(d != null && d.getFailureCode() != null ? d.getFailureCode().name() : null)
                    .failReason(d != null ? d.getFailReason() : null)
                    .build();
        }).toList();
    }

    private RouteReportResponse.Kpis computeKpis(
            Route route, List<RouteStop> stops, List<RouteReportResponse.StopRow> stopRows) {

        int completed = 0, partial = 0, failed = 0, failedAttempt = 0, replanned = 0, cancelled = 0;
        int onTime = 0, late = 0, early = 0;

        for (RouteReportResponse.StopRow r : stopRows) {
            // Pickup (multi-depot load) stops carry no deliveryId — they're logistics steps, not
            // deliveries, so they must not inflate completed/attempted/completion-rate.
            if (r.getDeliveryId() == null) continue;
            switch (r.getFinalStatus() == null ? "" : r.getFinalStatus()) {
                case "COMPLETED"          -> completed++;
                case "PARTIAL"            -> partial++;
                case "FAILED"             -> failed++;
                case "FAILED_ATTEMPT"     -> failedAttempt++;
                case "REMOVED_REPLANNED"  -> replanned++;
                case "REMOVED_CANCELLED"  -> cancelled++;
                default -> {}
            }
            switch (r.getClassification() == null ? "" : r.getClassification()) {
                case "ON_TIME" -> onTime++;
                case "LATE"    -> late++;
                case "EARLY"   -> early++;
                default -> {}
            }
        }

        int attempted = completed + partial + failed + failedAttempt;
        int totalPlanned = attempted + replanned + cancelled;

        BigDecimal completionRate = attempted > 0
                ? BigDecimal.valueOf((completed + partial) * 100.0 / attempted).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal onTimeRate = completed > 0
                ? BigDecimal.valueOf(onTime * 100.0 / completed).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal distanceKm = route.getTotalDistanceMeters() != null
                ? BigDecimal.valueOf(route.getTotalDistanceMeters() / 1000.0).setScale(2, RoundingMode.HALF_UP)
                : null;

        Integer activeMinutes = null;
        if (route.getStartedAt() != null && route.getClosedAt() != null) {
            activeMinutes = (int) Duration.between(route.getStartedAt(), route.getClosedAt()).toMinutes();
        }

        Integer cumDelay = delayCalculationService.calculateCumulativeDelayMinutes(route, stops);
        Integer startDelay = delayCalculationService.calculateRouteStartDelay(route);

        return RouteReportResponse.Kpis.builder()
                .totalStopsPlanned(totalPlanned)
                .attemptedStops(attempted)
                .completedStops(completed)
                .partialStops(partial)
                .failedStops(failed)
                .failedAttemptStops(failedAttempt)
                .replannedStops(replanned)
                .cancelledStopsCount(cancelled)
                .completionRate(completionRate)
                .onTimeRate(onTimeRate)
                .lateStops(late)
                .earlyStops(early)
                .onTimeStops(onTime)
                .cumulativeDelayMinutes(cumDelay)
                .totalDistanceKm(distanceKm)
                .activeDurationMinutes(activeMinutes)
                .routeStartDelayMinutes(startDelay)
                .build();
    }

    private List<RouteReportResponse.StatusBucket> buildStatusBreakdown(List<RouteReportResponse.StopRow> rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("COMPLETED", 0);
        counts.put("PARTIAL", 0);
        counts.put("FAILED_ALL", 0);
        counts.put("REPLANNED", 0);
        counts.put("CANCELLED", 0);

        int total = 0;
        for (RouteReportResponse.StopRow r : rows) {
            if (r.getDeliveryId() == null) continue; // exclude pickup (load) stops from the delivery donut
            total++;
            switch (r.getFinalStatus() == null ? "" : r.getFinalStatus()) {
                case "COMPLETED"          -> counts.merge("COMPLETED", 1, Integer::sum);
                case "PARTIAL"            -> counts.merge("PARTIAL", 1, Integer::sum);
                case "FAILED", "FAILED_ATTEMPT" -> counts.merge("FAILED_ALL", 1, Integer::sum);
                case "REMOVED_REPLANNED"  -> counts.merge("REPLANNED", 1, Integer::sum);
                case "REMOVED_CANCELLED"  -> counts.merge("CANCELLED", 1, Integer::sum);
                default -> {}
            }
        }
        final int deliveryTotal = total;
        List<RouteReportResponse.StatusBucket> buckets = new ArrayList<>();
        counts.forEach((key, count) -> {
            BigDecimal pct = deliveryTotal > 0
                    ? BigDecimal.valueOf(count * 100.0 / deliveryTotal).setScale(1, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            buckets.add(RouteReportResponse.StatusBucket.builder()
                    .key(key)
                    .label(labelFor(key))
                    .count(count)
                    .percentage(pct)
                    .build());
        });
        return buckets;
    }

    private List<RouteReportResponse.TimelinePoint> buildTimeline(
            List<RouteStop> stops,
            List<RouteReportResponse.StopRow> rows,
            Map<UUID, Delivery> deliveriesById) {

        Map<UUID, RouteReportResponse.StopRow> rowByStopId = rows.stream()
                .collect(Collectors.toMap(RouteReportResponse.StopRow::getStopId, r -> r));

        return stops.stream().map(s -> {
            RouteReportResponse.StopRow row = rowByStopId.get(s.getId());
            Delivery d = deliveriesById.get(s.getDeliveryId());
            String clientName = d != null && d.getOrder() != null ? d.getOrder().getClientName() : null;
            return RouteReportResponse.TimelinePoint.builder()
                    .stopOrder(s.getStopOrder())
                    .clientName(clientName)
                    .plannedEnd(s.getEndTimeWindow())
                    .actualAt(s.getCompletedAt())
                    .delayMinutes(row != null ? row.getDelayMinutes() : null)
                    .classification(row != null ? row.getClassification() : null)
                    .build();
        }).toList();
    }

    private List<RouteReportResponse.MovementEvent> buildMovements(
            List<RouteStop> stops,
            List<RouteReportResponse.StopRow> rows,
            Map<UUID, Delivery> deliveriesById) {

        Map<UUID, RouteReportResponse.StopRow> rowByStopId = rows.stream()
                .collect(Collectors.toMap(RouteReportResponse.StopRow::getStopId, r -> r));

        List<RouteReportResponse.MovementEvent> events = new ArrayList<>();

        for (RouteStop s : stops) {
            RouteReportResponse.StopRow row = rowByStopId.get(s.getId());
            String clientName = row != null ? row.getClientName() : null;

            if (s.getRemovedAt() != null) {
                String type = "REMOVED_REPLANNED".equals(s.getStatus().name())
                        ? "STOP_REMOVED_REPLANNED" : "STOP_REMOVED_CANCELLED";
                String detail = type.equals("STOP_REMOVED_REPLANNED")
                        ? "Arrêt #" + s.getStopOrder() + " retiré · Replanifié"
                          + (row != null && row.getMovementTarget() != null
                                ? " vers «" + row.getMovementTarget() + "»" : "")
                        : "Arrêt #" + s.getStopOrder() + " annulé"
                          + (s.getRemovedReason() != null && !s.getRemovedReason().isBlank()
                                ? " · " + s.getRemovedReason() : "");
                events.add(RouteReportResponse.MovementEvent.builder()
                        .at(s.getRemovedAt())
                        .type(type)
                        .stopOrder(s.getStopOrder())
                        .clientName(clientName)
                        .actor(s.getRemovedBy())
                        .detail(detail)
                        .build());
            }

            if (s.getHandoffConfirmedAt() != null) {
                String from = row != null ? row.getHandoffFromDriverName() : null;
                String to   = row != null ? row.getHandoffToDriverName() : null;
                events.add(RouteReportResponse.MovementEvent.builder()
                        .at(s.getHandoffConfirmedAt())
                        .type("HANDOFF_CONFIRMED")
                        .stopOrder(s.getStopOrder())
                        .clientName(clientName)
                        // The receiving driver confirms the handoff (QR scan) — name them, not a generic role.
                        .actor(to != null && !to.isBlank() ? to : "DRIVER")
                        .detail("Arrêt #" + s.getStopOrder() + " transféré"
                                + (from != null ? " : " + from : "")
                                + (to != null ? " → " + to : "")
                                + " · QR confirmé")
                        .build());
            }

            Delivery d = deliveriesById.get(s.getDeliveryId());
            if (d != null && "FAILED".equals(s.getStatus().name()) && d.getFailedAt() != null) {
                String code = d.getFailureCode() != null ? d.getFailureCode().getLabel() : "Échec";
                // The attempting driver failed the stop — resolve their name (fallback to the role).
                String failActor = "DRIVER";
                if (d.getDriverId() != null) {
                    DriverDTO drv = safeGetDriver(d.getDriverId().toString());
                    if (drv != null && drv.getName() != null && !drv.getName().isBlank()) failActor = drv.getName();
                }
                events.add(RouteReportResponse.MovementEvent.builder()
                        .at(d.getFailedAt())
                        .type("STOP_FAILED")
                        .stopOrder(s.getStopOrder())
                        .clientName(clientName)
                        .actor(failActor)
                        .detail("Arrêt #" + s.getStopOrder() + " échec · " + code
                                + (d.getFailReason() != null && !d.getFailReason().isBlank()
                                        ? " · « " + d.getFailReason() + " »" : ""))
                        .build());
            }
        }

        events.sort(Comparator.comparing(
                RouteReportResponse.MovementEvent::getAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    private List<RouteReportResponse.PodEntry> buildPodGallery(
            List<RouteStop> stops, Map<UUID, Delivery> deliveriesById) {

        List<RouteReportResponse.PodEntry> gallery = new ArrayList<>();
        for (RouteStop s : stops) {
            if (s.getDeliveryId() == null) continue;
            podRepository.findByDeliveryId(s.getDeliveryId()).ifPresent(pod -> {
                Delivery d = deliveriesById.get(s.getDeliveryId());
                String clientName = d != null && d.getOrder() != null ? d.getOrder().getClientName() : null;
                gallery.add(RouteReportResponse.PodEntry.builder()
                        .stopId(s.getId())
                        .deliveryId(s.getDeliveryId())
                        .stopOrder(s.getStopOrder())
                        .clientName(clientName)
                        .photoUrl(presignSafe(pod.getPhotoUrl()))
                        .signatureUrl(presignSafe(pod.getSignatureUrl()))
                        .bonLivraisonUrl(presignSafe(pod.getBonLivraisonPhotoUrl()))
                        .lat(pod.getLat())
                        .lng(pod.getLng())
                        .collectedAt(pod.getCollectedAt())
                        .comment(pod.getComment())
                        .build());
            });
        }
        return gallery;
    }

    private List<RouteReportResponse.AuditEntry> buildAuditTrail(List<RouteStop> stops) {
        // Step 1 — collect all events
        record Raw(LocalDateTime at, String actor, String role, Integer stopOrder, String eventKey, String eventParams) {}
        List<Raw> raw = new ArrayList<>();
        List<DeliveryStatusHistory> allRows = new ArrayList<>();

        for (RouteStop s : stops) {
            if (s.getDeliveryId() == null) continue;
            for (DeliveryStatusHistory h : statusHistoryRepository.findByDeliveryIdOrderByChangedAtAsc(s.getDeliveryId())) {
                allRows.add(h);
                raw.add(new Raw(
                        h.getChangedAt(),
                        h.getChangedBy(),
                        h.getChangedByRole() != null ? h.getChangedByRole().name() : null,
                        s.getStopOrder(),
                        h.getEventKey(),
                        h.getEventParams()
                ));
            }
        }

        // Step 2 — batch-resolve every actor (driver + admin/dispatcher UUIDs, and literal names)
        // through the shared resolver, so admin actions show the real name, not a UUID fragment.
        Map<String, String> actorNames = actorNameResolver.prefetch(allRows);

        // Step 3 — format French actions from structured events
        List<RouteReportResponse.AuditEntry> entries = new ArrayList<>();
        for (Raw r : raw) {
            String actor = actorNameResolver.resolve(r.actor(), null, actorNames);
            entries.add(RouteReportResponse.AuditEntry.builder()
                    .at(r.at)
                    .actor(actor)
                    .role(r.role)
                    .action("Arrêt #" + r.stopOrder + " · " + buildActionFromEventKey(r.eventKey))
                    .detail(extractEventDetail(r.eventParams))
                    .build());
        }

        entries.sort(Comparator.comparing(
                RouteReportResponse.AuditEntry::getAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        if (entries.size() > 50) {
            entries = entries.subList(entries.size() - 50, entries.size());
        }
        return entries;
    }

    private static boolean looksLikeUuid(String s) {
        return s != null && s.length() == 36 && s.charAt(8) == '-' && s.charAt(13) == '-';
    }

    /** Map eventKey to French action descriptions for audit trail. */
    private static String buildActionFromEventKey(String eventKey) {
        if (eventKey == null) return "Événement inconnu";
        return switch (eventKey) {
            case "DELIVERY_CREATED"              -> "Créée";
            case "DELIVERY_IMPORTED"             -> "Importée";
            case "DELIVERY_SCHEDULED"            -> "Planifiée";
            case "DELIVERY_SCHEDULED_BY_DRIVER"  -> "Planifiée par le chauffeur";
            case "DELIVERY_PICKED_UP"            -> "Récupérée";
            case "DELIVERY_TRANSIT_STARTED"      -> "Départ en transit";
            case "DELIVERY_COMPLETED"            -> "Livrée";
            case "DELIVERY_PARTIALLY_DELIVERED"  -> "Livrée partiellement";
            case "DELIVERY_FAILED"               -> "Échouée";
            case "DELIVERY_CANCELLED"            -> "Annulée";
            case "DELIVERY_REPLANNED"            -> "Replanifiée";
            case "ROUTE_VALIDATED_ASSIGNED"      -> "Tournée validée · affectée";
            case "ROUTE_STARTED_AUTO_PICKUP"     -> "Ramassage auto au départ";
            case "ROUTE_STOP_ADDED"              -> "Ajoutée à la tournée";
            case "ROUTE_STOP_REMOVED"            -> "Retirée de la tournée";
            case "ROUTE_STOP_CANCELLED"          -> "Arrêt annulé";
            case "ROUTE_CANCELLED"               -> "Tournée annulée";
            case "RETURN_TO_ORIGIN_CONFIRMED"    -> "Retour au dépôt confirmé";
            default                              -> eventKey;
        };
    }

    /** Extract relevant details from structured event payload (driver UUID → name, route, reason, note). */
    private String extractEventDetail(String eventParams) {
        if (eventParams == null || eventParams.isBlank() || eventParams.equals("{}")) {
            return null;
        }
        try {
            var params = objectMapper.readValue(eventParams, java.util.Map.class);
            var details = new java.util.ArrayList<String>();
            Object routeName = params.get("routeName");
            if (routeName != null && !routeName.toString().isBlank()) {
                details.add("Tournée: " + routeName);
            }
            if (params.get("driverId") != null) {
                String id = String.valueOf(params.get("driverId"));
                DriverDTO d = safeGetDriver(id);
                String name = (d != null && d.getName() != null && !d.getName().isBlank())
                        ? d.getName()
                        : (looksLikeUuid(id) ? id.substring(0, 8) : id);
                details.add("Chauffeur: " + name);
            }
            if (params.get("reason") != null) {
                details.add("Motif: " + params.get("reason"));
            }
            if (params.get("note") != null) {
                details.add("Note: " + params.get("note"));
            }
            return details.isEmpty() ? null : String.join(" | ", details);
        } catch (Exception e) {
            return null;
        }
    }

    /** Map raw DeliveryStatus enum names to French labels for audit display. */
    private static String deliveryStatusFr(String status) {
        if (status == null) return "—";
        return switch (status) {
            case "UNSCHEDULED"          -> "Non planifiée";
            case "SCHEDULED"            -> "Planifiée";
            case "PICKED_UP"            -> "Récupérée";
            case "IN_TRANSIT"           -> "En transit";
            case "DELIVERED"            -> "Livrée";
            case "PARTIALLY_DELIVERED"  -> "Partiellement livrée";
            case "FAILED"               -> "Échouée";
            case "CANCELLED"            -> "Annulée";
            default                      -> status;
        };
    }

    /** Translate the common English notes baked into the DB by legacy code. */
    private static String translateNoteFr(String note) {
        if (note == null || note.isBlank()) return null;
        return switch (note) {
            case "Imported from ERP via Adapter"            -> "Importée depuis l'ERP";
            case "Route validated and delivery assigned"    -> "Tournée validée et livraison assignée";
            case "Route started and package auto-picked up" -> "Tournée démarrée, colis pris en charge";
            case "Driver started transit"                   -> "Départ en transit";
            case "Delivery completed"                       -> "Livraison terminée";
            case "Delivery failed"                          -> "Livraison échouée";
            case "Delivery cancelled"                       -> "Livraison annulée";
            case "Driver cancelled, reassigning"            -> "Annulée par le chauffeur, à réassigner";
            case "Workflow: timeout reset"                  -> "Réinitialisation auto (timeout)";
            default                                         -> note;
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Classify a stop into one of: ON_TIME | LATE | EARLY | PARTIAL | FAILED |
     * FAILED_ATTEMPT | REPLANNED | CANCELLED. Drives the donut, timeline, and pills.
     */
    private String classify(RouteStop s, Integer delayMinutes) {
        if (s.getStatus() == null) return null;
        return switch (s.getStatus()) {
            case COMPLETED -> {
                if (delayMinutes == null) yield "ON_TIME";
                if (delayMinutes > 0) yield "LATE";
                if (delayMinutes < -5) yield "EARLY";
                yield "ON_TIME";
            }
            case PARTIAL              -> "PARTIAL";
            case FAILED               -> "FAILED";
            case FAILED_ATTEMPT       -> "FAILED_ATTEMPT";
            case REMOVED_REPLANNED    -> "REPLANNED";
            case REMOVED_CANCELLED    -> "CANCELLED";
            default                    -> "PENDING";
        };
    }

    private String resolveMovement(RouteStop s) {
        if (s.getHandoffConfirmedAt() != null) return "HANDOFF";
        if (s.getStatus() == null) return null;
        return switch (s.getStatus()) {
            case REMOVED_REPLANNED -> "REPLANNED";
            case REMOVED_CANCELLED -> "CANCELLED";
            default -> null;
        };
    }

    /** Resolve the destination route name for a REPLANNED stop, or null. */
    private String resolveMovementTarget(RouteStop s) {
        if (s.getHandoffToDriverId() != null) {
            DriverDTO d = safeGetDriver(s.getHandoffToDriverId().toString());
            return d != null ? d.getName() : null;
        }
        if (s.getDeliveryId() == null) return null;
        if (s.getStatus() != RouteStopStatus.REMOVED_REPLANNED) return null;
        // The new home of the delivery: another RouteStop with the same deliveryId, not in removed states.
        return routeStopRepository.findByDeliveryIdWithRoute(s.getDeliveryId())
                .filter(rs -> rs.getStatus() != RouteStopStatus.REMOVED_REPLANNED
                            && rs.getStatus() != RouteStopStatus.REMOVED_CANCELLED)
                .map(rs -> rs.getRoute() != null ? rs.getRoute().getName() : null)
                .orElse(null);
    }

    private DriverDTO safeGetDriver(String driverId) {
        try {
            return transportPort.getDriver(driverId);
        } catch (Exception e) {
            log.debug("transportPort.getDriver({}) failed: {}", driverId, e.getMessage());
            return null;
        }
    }

    /**
     * POD photo URLs are stored as MinIO public URLs by ProofOfDeliveryService.
     * If we received an object key (no http prefix), convert it via getPublicUrl.
     */
    private String presignSafe(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            if (!url.startsWith("http")) return minioStorageService.getPublicUrl(url);
        } catch (Exception e) {
            log.debug("Public URL conversion failed for {}: {}", url, e.getMessage());
        }
        return url;
    }

    private String labelFor(String key) {
        return switch (key) {
            case "COMPLETED"  -> "Livrés";
            case "PARTIAL"    -> "Partiels";
            case "FAILED_ALL" -> "Échoués";
            case "REPLANNED"  -> "Replanifiés";
            case "CANCELLED"  -> "Annulés";
            default            -> key;
        };
    }
}
