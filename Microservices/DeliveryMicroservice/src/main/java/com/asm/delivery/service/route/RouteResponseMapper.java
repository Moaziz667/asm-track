package com.asm.delivery.service.route;

import com.asm.delivery.dto.response.*;
import com.asm.delivery.entity.*;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DeliveryStatusHistoryRepository;
import com.asm.delivery.repository.DepotRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.VehicleRepository;
import com.asm.delivery.repository.ZoneRepository;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.service.ProofOfDeliveryService;
import com.asm.delivery.transport.DriverDTO;
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
import java.time.LocalDateTime;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assembles route/stop entities into their API response DTOs (flat {@link RouteResponse} for lists,
 * {@link RouteFullResponse} with nested delivery/order/POD/history for the detail view). Pure
 * read-side mapping: no entity is mutated and nothing is persisted. Extracted from
 * RoutePlanningService so the ~500 lines of builder plumbing live apart from the planning mutations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RouteResponseMapper {

    private final RouteStopRepository routeStopRepository;
    private final DeliveryRepository deliveryRepository;
    private final DepotRepository depotRepository;
    private final ZoneRepository zoneRepository;
    private final VehicleRepository vehicleRepository;
    private final DelayCalculationService delayCalculationService;
    private final ProofOfDeliveryService proofOfDeliveryService;
    private final DeliveryStatusHistoryRepository deliveryStatusHistoryRepository;
    private final com.asm.delivery.sla.SlaStateRepository slaStateRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public RouteResponse toResponse(Route route) {
        List<RouteStop> routeStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());

        Map<UUID, Delivery> deliveriesById = new HashMap<>();
        List<UUID> deliveryIds = routeStops.stream().filter(s -> s.getDeliveryId() != null).map(RouteStop::getDeliveryId).toList();
        if (!deliveryIds.isEmpty()) {
            deliveriesById = deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                    .collect(Collectors.toMap(Delivery::getId, Function.identity(), (existing, replacement) -> existing));
        }

        Set<UUID> depotIds = new HashSet<>();
        if (route.getDepotId() != null) depotIds.add(route.getDepotId());
        for (RouteStop s : routeStops) {
            if (s.getSourceDepotId() != null) depotIds.add(s.getSourceDepotId());
            Delivery d = s.getDeliveryId() != null ? deliveriesById.get(s.getDeliveryId()) : null;
            if (d != null && d.getSourceDepotId() != null) depotIds.add(d.getSourceDepotId());
        }
        Map<UUID, com.asm.delivery.entity.Depot> depotMap = depotIds.isEmpty() ? Map.of() :
                depotRepository.findAllById(depotIds).stream()
                .collect(Collectors.toMap(com.asm.delivery.entity.Depot::getId, Function.identity()));

        // Separate active and legacy stops
        List<RouteStop> activeStops = routeStops.stream()
                .filter(s -> !RoutePlanningService.isRemovedStatus(s.getStatus()))
                .toList();
        List<RouteStop> legacyStops = routeStops.stream()
                .filter(s -> RoutePlanningService.isRemovedStatus(s.getStatus()))
                .toList();

        List<RouteStopResponse> stops = new ArrayList<>();
        for (RouteStop stop : activeStops) {
            Delivery delivery = deliveriesById.get(stop.getDeliveryId());
            Order order = delivery != null ? delivery.getOrder() : null;
            boolean isPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;

                // Calculate delay info for this stop
                DelayCalculationService.DelayInfo delayInfo = delayCalculationService.calculateDelay(stop, route, activeStops);
                SlaStatus preferredSla = delayInfo != null && delayInfo.delayStatus != null
                    ? SlaStatus.valueOf(delayInfo.delayStatus)
                    : null;

                UUID sourceDepotId = stop.getStopType() == RouteStopType.PICKUP ? stop.getSourceDepotId() :
                                     (delivery != null ? delivery.getSourceDepotId() : null);
                com.asm.delivery.entity.Depot sourceDepot = sourceDepotId != null ? depotMap.get(sourceDepotId) : null;

                stops.add(RouteStopResponse.builder()
                    .id(stop.getId())
                    .deliveryId(stop.getDeliveryId())
                    .stopType(stop.getStopType())
                    .sourceDepotId(sourceDepotId)
                    .sourceDepotName(sourceDepot != null ? sourceDepot.getName() : null)
                    .sourceDepotLat(sourceDepot != null ? sourceDepot.getLatitude() : null)
                    .sourceDepotLng(sourceDepot != null ? sourceDepot.getLongitude() : null)
                    .stopOrder(stop.getStopOrder())
                    .status(stop.getStatus())
                    .arrivedAt(stop.getArrivedAt())
                    .completedAt(stop.getCompletedAt())
                    .notes(stop.getNotes())
                    .deliveryAddress(order != null ? order.getDropoffAddress() : null)
                    .deliveryCity(order != null ? order.getDropoffCity() : null)
                    .deliveryPostalCode(order != null ? order.getDropoffPostalCode() : null)
                    .deliveryCountryCode(order != null ? order.getDropoffCountryCode() : null)
                    .dropoffLat(order != null ? order.getDropoffLat() : null)
                    .dropoffLng(order != null ? order.getDropoffLng() : null)
                    .dropoffPinned(isPinned)
                    .routeGeometry(stop.getRouteGeometry())
                    .routeDistanceKm(delivery != null ? delivery.getRouteDistanceKm() : null)
                    .routeDurationMinutes(delivery != null ? delivery.getRouteDurationMinutes() : null)
                    .routeEtaAt(delivery != null ? delivery.getRouteEtaAt() : null)
                    .transitSlaMinutesComputed(delivery != null ? delivery.getTransitSlaMinutesComputed() : null)
                    .routeProvider(delivery != null ? delivery.getRouteProvider() : null)
                    .etaAt(stop.getEtaAt())
                    .slaDeadline(stop.getEtaBufferAt())
                    .slaStatus(preferredSla)
                    .driveDurationSeconds(stop.getDriveDurationSeconds())
                    .driveDistanceMeters(stop.getDriveDistanceMeters())
                    .actualArrivalAt(stop.getActualArrivalAt())
                    .dwellMinutes(stop.getDwellMinutes())
                    .actualDwellMinutes(stop.getActualDwellMinutes())
                    .completionStatus(stop.getCompletionStatus())
                    .bufferMinutes(stop.getBufferMinutes())
                    .startTimeWindow(stop.getStartTimeWindow())
                    .endTimeWindow(stop.getEndTimeWindow())
                    .deliveryStatus(delivery != null && delivery.getStatus() != null ? delivery.getStatus().name() : null)
                    .clientName(order != null ? order.getClientName() : null)
                    .clientPhone(order != null ? order.getClientPhone() : null)
                    .totalAmount(order != null ? order.getTotalAmount() : null)
                    .orderRef(order != null ? order.resolveRef() : null)
                    .delayMinutes(delayInfo != null ? delayInfo.delayMinutes : null)
                    .delayStatus(delayInfo != null ? delayInfo.delayStatus : null)
                    .delayReason(delayInfo != null ? delayInfo.delayReason : null)
                    .build());
        }

        // Build legacy stops responses
        List<RouteStopResponse> legacyStopResponses = new ArrayList<>();
        for (RouteStop stop : legacyStops) {
            Delivery delivery = deliveriesById.get(stop.getDeliveryId());
            Order order = delivery != null ? delivery.getOrder() : null;
            legacyStopResponses.add(RouteStopResponse.builder()
                    .id(stop.getId())
                    .deliveryId(stop.getDeliveryId())
                    .stopOrder(stop.getStopOrder())
                    .status(stop.getStatus())
                    .deliveryAddress(order != null ? order.getDropoffAddress() : null)
                    .deliveryCity(order != null ? order.getDropoffCity() : null)
                    .clientName(order != null ? order.getClientName() : null)
                    .orderRef(order != null ? order.resolveRef() : null)
                    .removedAt(stop.getRemovedAt())
                    .removedReason(stop.getRemovedReason())
                    .removedBy(stop.getRemovedBy())
                    .build());
        }

                int totalStops = stops.size();
                int completedStops = (int) stops.stream().filter(s -> s.getStatus() == RouteStopStatus.COMPLETED).count();
                int failedStops = (int) stops.stream().filter(s -> s.getStatus() == RouteStopStatus.FAILED).count();
                int partialStops = (int) stops.stream().filter(s -> s.getStatus() == RouteStopStatus.PARTIAL).count();
                int pendingStops = Math.max(totalStops - completedStops - failedStops - partialStops, 0);
                double progressPercent = totalStops == 0
                    ? 0.0
                    : ((double) (completedStops + failedStops + partialStops) * 100.0) / (double) totalStops;

                // Calculate route start delay
                Integer routeStartDelayMinutes = delayCalculationService.calculateRouteStartDelay(route);
                Integer cumulativeDelayMinutes = delayCalculationService.calculateCumulativeDelayMinutes(route, activeStops);
                Double onTimeCompletionRate = delayCalculationService.calculateOnTimeCompletionRate(route, activeStops);

                LocalDateTime plannedEndDateTime = route.getDate() != null && route.getPlannedEndTime() != null
                    ? route.getDate().atTime(route.getPlannedEndTime())
                    : null;
                Long etaDriftMinutes = null;
                if (plannedEndDateTime != null) {
                    LocalDateTime reference = route.getStatus() == RouteStatus.CLOSED && route.getClosedAt() != null
                        ? route.getClosedAt()
                        : LocalDateTime.now();
                    long drift = java.time.Duration.between(plannedEndDateTime, reference).toMinutes();
                    etaDriftMinutes = Math.max(drift, 0);
                }

        // Auto-detect zones from stop postal codes (batch query)
        List<String> postalCodes = stops.stream()
                .map(RouteStopResponse::getDeliveryPostalCode)
                .filter(pc -> pc != null && !pc.isBlank())
                .distinct()
                .toList();

        List<Zone> detectedZones = postalCodes.isEmpty()
                ? List.of()
                : zoneRepository.findActiveZonesByPostalCodes( postalCodes.toArray(new String[0]));
        List<String> detectedZoneNames = detectedZones.stream().map(Zone::getName).toList();
        String detectedZoneLabel = detectedZoneNames.isEmpty()
                ? ""
                : String.join(" · ", detectedZoneNames);

        com.asm.delivery.entity.Depot depot = route.getDepotId() != null
                ? depotMap.get(route.getDepotId()) : null;

        // Capacity context for capacity-aware reassignment: vehicle payload + current planned load
        // (sum of non-removed delivery weights). Lets the dispatch desk show a load/capacity bar.
        Integer payloadKg = null;
        if (route.getVehicleId() != null) {
            Vehicle v = vehicleRepository.findById(route.getVehicleId()).orElse(null);
            if (v != null) payloadKg = v.getPayloadKg();
        }
        java.math.BigDecimal loadKg = java.math.BigDecimal.ZERO;
        for (RouteStop s : activeStops) {
            if (s.getStopType() == RouteStopType.DELIVERY && s.getDeliveryId() != null) {
                Delivery d = deliveriesById.get(s.getDeliveryId());
                if (d != null && d.getOrder() != null && d.getOrder().getTotalWeightKg() != null) {
                    loadKg = loadKg.add(d.getOrder().getTotalWeightKg());
                }
            }
        }

        return RouteResponse.builder()
                .id(route.getId())

                .name(route.getName())
                .driverId(route.getDriverId())
                .vehicleId(route.getVehicleId())
                .payloadKg(payloadKg)
                .currentLoadKg(loadKg.doubleValue())
                .date(route.getDate())
                .depotId(route.getDepotId())
                .depotName(depot != null ? depot.getName() : null)
                .depotAddress(depot != null ? depot.getAddress() : null)
                .depotLatitude(depot != null ? depot.getLatitude() : null)
                .depotLongitude(depot != null ? depot.getLongitude() : null)
                .plannedStartTime(route.getPlannedStartTime())
                .plannedEndTime(route.getPlannedEndTime())
                .city(route.getCity())
                .status(route.getStatus())
                .createdBy(route.getCreatedBy())
                .createdAt(route.getCreatedAt())
                .validatedAt(route.getValidatedAt())
                .startedAt(route.getStartedAt())
                .closedAt(route.getClosedAt())
                .totalStops(totalStops)
                .completedStops(completedStops)
                .failedStops(failedStops)
                .partialStops(partialStops)
                .pendingStops(pendingStops)
                .progressPercent(progressPercent)
                .etaDriftMinutes(etaDriftMinutes)
                .cumulativeDelayMinutes(cumulativeDelayMinutes)
                .onTimeCompletionRate(onTimeCompletionRate)
                .stops(stops)
                .depotId(route.getDepotId())
                .depotName(depot != null ? depot.getName() : null)
                .depotAddress(depot != null ? depot.getAddress() : null)
                .departureTime(route.getDepartureTime())
                .totalDurationSeconds(route.getTotalDurationSeconds())
                .totalDistanceMeters(route.getTotalDistanceMeters())
                .isOptimized(route.getIsOptimized())
                .routeGeometry(route.getRouteGeometry())
                .detectedZoneLabel(detectedZoneLabel)
                .detectedZoneNames(detectedZoneNames)
                .routeStartDelayMinutes(routeStartDelayMinutes)
                .legacyStops(legacyStopResponses.isEmpty() ? null : legacyStopResponses)
                .routeVersion(route.getRouteVersion())
                .locked(Boolean.TRUE.equals(route.getLocked()))
                .build();
    }

    public RouteFullResponse toFullResponse(Route route, DriverDTO driverData, Map<UUID, Delivery> deliveryMap) {
        // --- Same initial logic as toResponse ---
        // Load stops from repository (guarantees stopOrder ASC, consistent with toResponse)
        List<RouteStop> allStops = route.getStops(); // Already fetched via findFullRouteById
        List<RouteStop> activeStops = allStops.stream()
                .filter(stop -> !RoutePlanningService.isRemovedStatus(stop.getStatus()))
                .toList();

        Map<String, String> actorNames = new HashMap<>();
        // Note: Driver name resolution for history is omitted for brevity or handled by building a map if needed.
        // For PFE, we prioritize stability (fixing the LazyInit crash).

        // Pre-load SLA state + source depots for all stops once, so toFullStopResponse does no
        // per-stop SLA/depot query (was N+1: two SlaState reads + one depot read per stop).
        List<UUID> allDeliveryIds = allStops.stream()
                .map(RouteStop::getDeliveryId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, com.asm.delivery.sla.SlaState> slaByDeliveryId = allDeliveryIds.isEmpty() ? Map.of()
                : slaStateRepository.findByDeliveryIdIn(allDeliveryIds).stream()
                    .collect(Collectors.toMap(com.asm.delivery.sla.SlaState::getDeliveryId, Function.identity(), (a, b) -> a));

        Set<UUID> depotIds = new HashSet<>();
        for (RouteStop s : allStops) {
            if (s.getSourceDepotId() != null) depotIds.add(s.getSourceDepotId());
            Delivery d = s.getDeliveryId() != null ? deliveryMap.get(s.getDeliveryId()) : null;
            if (d != null && d.getSourceDepotId() != null) depotIds.add(d.getSourceDepotId());
        }
        Map<UUID, com.asm.delivery.entity.Depot> depotById = depotIds.isEmpty() ? Map.of()
                : depotRepository.findAllById(depotIds).stream()
                    .collect(Collectors.toMap(com.asm.delivery.entity.Depot::getId, Function.identity()));

        List<RouteStopFullResponse> stops = activeStops.stream()
                .map(stop -> toFullStopResponse(stop, route, activeStops, actorNames, deliveryMap, slaByDeliveryId, depotById))
                .toList();

        List<RouteStopFullResponse> legacyStops = allStops.stream()
                .filter(stop -> RoutePlanningService.isRemovedStatus(stop.getStatus()))
                .map(stop -> toFullStopResponse(stop, route, activeStops, actorNames, deliveryMap, slaByDeliveryId, depotById))
                .toList();

        int totalActiveStops = activeStops.size();

        long completed = activeStops.stream()
                .filter(s -> s.getStatus() == RouteStopStatus.COMPLETED)
                .count();
        long failed = activeStops.stream()
                .filter(s -> s.getStatus() == RouteStopStatus.FAILED)
                .count();
        long partial = activeStops.stream()
                .filter(s -> s.getStatus() == RouteStopStatus.PARTIAL)
                .count();
        long pending = totalActiveStops - (completed + failed + partial);

        double progress = totalActiveStops == 0 ? 0.0 :
                (double) (completed + failed + partial) / totalActiveStops * 100.0;

        List<String> deliveryIds = activeStops.stream()
                .filter(s -> s.getDeliveryId() != null)
                .map(s -> s.getDeliveryId().toString())
                .toList();

        String detectedZoneLabel = null;
        List<String> detectedZoneNames = new java.util.ArrayList<>();
        if (route.getZoneId() != null) {
            com.asm.delivery.entity.Zone z = zoneRepository.findById( route.getZoneId()).orElse(null);
            if (z != null) {
                detectedZoneNames.add(z.getName());
            }
            detectedZoneLabel = detectedZoneNames.isEmpty() ? null : detectedZoneNames.get(0);
        } else if (!deliveryIds.isEmpty()) {
            List<Delivery> deliveries = deliveryRepository.findAllById(
                    deliveryIds.stream().map(UUID::fromString).toList()
            );
            detectedZoneNames = deliveries.stream()
                    .map(d -> {
                        if (d.getOrder() != null && d.getOrder().getZoneId() != null) {
                            return zoneRepository.findById(d.getOrder().getZoneId())
                                .map(com.asm.delivery.entity.Zone::getName)
                                .orElse(null);
                        }
                        return null;
                    })
                    .filter(z -> z != null && !z.trim().isEmpty())
                    .distinct()
                    .toList();
            if (!detectedZoneNames.isEmpty()) {
                detectedZoneLabel = String.join(", ", detectedZoneNames);
            }
        }

        Integer routeStartDelayMinutes = route.getStartedAt() != null && route.getPlannedStartTime() != null
                ? (int) java.time.Duration.between(LocalDateTime.of(route.getDate(), route.getPlannedStartTime()), route.getStartedAt()).toMinutes()
                : null;
        // ----------------------------------------

        Integer cumulativeDelayMinutes = null;
        Double onTimeCompletionRate = null;

        if (route.getStatus() == RouteStatus.IN_PROGRESS || route.getStatus() == RouteStatus.CLOSED) {
            cumulativeDelayMinutes = delayCalculationService.calculateCumulativeDelayMinutes(route, activeStops);
            onTimeCompletionRate = delayCalculationService.calculateOnTimeCompletionRate(route, activeStops);
        }

        return RouteFullResponse.builder()
                .id(route.getId())
                .name(route.getName())
                .date(route.getDate())
                .plannedStartTime(route.getPlannedStartTime())
                .plannedEndTime(route.getPlannedEndTime())
                .city(route.getCity())
                .status(route.getStatus())
                .createdBy(route.getCreatedBy())
                .createdAt(route.getCreatedAt())
                .validatedAt(route.getValidatedAt())
                .startedAt(route.getStartedAt())
                .closedAt(route.getClosedAt())
                .totalStops(totalActiveStops)
                .completedStops((int) completed)
                .failedStops((int) failed)
                .partialStops((int) partial)
                .pendingStops((int) pending)
                .progressPercent(Math.round(progress * 100.0) / 100.0)
                .cumulativeDelayMinutes(cumulativeDelayMinutes != null ? cumulativeDelayMinutes : route.getCumulativeDelayMinutes())
                .onTimeCompletionRate(onTimeCompletionRate != null ? onTimeCompletionRate : (route.getRouteOnTimeCompletionRate() != null ? route.getRouteOnTimeCompletionRate().doubleValue() : null))
                .routeStartDelayMinutes(routeStartDelayMinutes)
                .stops(stops)
                .legacyStops(legacyStops.isEmpty() ? null : legacyStops)
                .driver(buildDriverResponseLocal(route.getDriverId(), driverData))
                .vehicle(buildVehicleResponse(route.getVehicleId()))
                .depot(route.getDepotId() != null ? depotRepository.findById(route.getDepotId())
                        .map(depot -> com.asm.delivery.dto.response.DepotResponse.builder()
                                .id(depot.getId())
                                .name(depot.getName())
                                .address(depot.getAddress())
                                .latitude(depot.getLatitude())
                                .longitude(depot.getLongitude())
                                .isActive(depot.getIsActive())
                                .createdAt(depot.getCreatedAt())
                                .updatedAt(depot.getUpdatedAt())
                                .build())
                        .orElse(null) : null)
                .departureTime(route.getDepartureTime())
                .totalDurationSeconds(route.getTotalDurationSeconds())
                .totalDistanceMeters(route.getTotalDistanceMeters())
                .isOptimized(route.getIsOptimized())
                .routeGeometry(route.getRouteGeometry())
                .detectedZoneLabel(detectedZoneLabel)
                .detectedZoneNames(detectedZoneNames)
                .routeVersion(route.getRouteVersion())
                .build();
    }

    private AdminDriverResponse buildDriverResponseLocal(UUID driverId, DriverDTO dto) {
        if (driverId == null) return null;
        if (dto == null) return AdminDriverResponse.builder().id(driverId).build();
        return AdminDriverResponse.builder()
                .id(driverId)
                .name(dto.getName())
                .phone(dto.getPhone())
                .currentLat(dto.getCurrentLat() != null ? java.math.BigDecimal.valueOf(dto.getCurrentLat()) : null)
                .currentLng(dto.getCurrentLng() != null ? java.math.BigDecimal.valueOf(dto.getCurrentLng()) : null)
                .build();
    }

    private com.asm.delivery.dto.response.VehicleResponse buildVehicleResponse(UUID vehicleId) {
        if (vehicleId == null) return null;
        return vehicleRepository.findById(vehicleId).map(v ->
                com.asm.delivery.dto.response.VehicleResponse.builder()
                        .id(v.getId())
                        .name(v.getName())
                        .plate(v.getPlate())
                        .make(v.getMake())
                        .model(v.getModel())
                        .payloadKg(v.getPayloadKg())
                        .volumeM3(v.getVolumeM3())
                        .type(v.getType())
                        .active(v.getActive())
                        .driverId(v.getDriverId())
                        .build()
        ).orElse(com.asm.delivery.dto.response.VehicleResponse.builder().id(vehicleId).build());
    }

    private RouteStopFullResponse toFullStopResponse(RouteStop stop, Route route, List<RouteStop> activeStops, Map<String, String> actorNames, Map<UUID, Delivery> deliveryMap,
                                                     Map<UUID, com.asm.delivery.sla.SlaState> slaByDeliveryId,
                                                     Map<UUID, com.asm.delivery.entity.Depot> depotById) {
        Delivery delivery = stop.getDeliveryId() != null ? deliveryMap.get(stop.getDeliveryId()) : null;
        if (delivery == null && stop.getDeliveryId() != null) {
            // Fallback for safety, though it shouldn't happen with the pre-fetch
            delivery = deliveryRepository.findByIdWithOrder(stop.getDeliveryId()).orElse(null);
        }

        com.asm.delivery.entity.Order orderInfo = delivery != null ? delivery.getOrder() : null;
        com.asm.delivery.sla.SlaState slaState = stop.getDeliveryId() != null ? slaByDeliveryId.get(stop.getDeliveryId()) : null;

        // Calculate delay details
        DelayCalculationService.DelayInfo delayInfo = delayCalculationService.calculateDelay(stop, route, activeStops);
        Integer delayMinutes = delayInfo != null ? delayInfo.delayMinutes : null;
        String delayStatus = delayInfo != null ? delayInfo.delayStatus : null;
        String delayReason = delayInfo != null ? delayInfo.delayReason : null;
        Integer transitSlaMinutesComputed = null; // Removed as it is not in DelayInfo


        UUID sourceDepotId = stop.getStopType() == RouteStopType.PICKUP ? stop.getSourceDepotId() :
                             (delivery != null ? delivery.getSourceDepotId() : null);
        com.asm.delivery.entity.Depot sourceDepot = sourceDepotId != null ? depotById.get(sourceDepotId) : null;

        // Parcel count for PICKUP stops: count DELIVERY stops whose source depot matches
        int parcelCount = 0;
        if (stop.getStopType() == RouteStopType.PICKUP && stop.getSourceDepotId() != null) {
            for (RouteStop rs : activeStops) {
                if (rs.getStopType() == RouteStopType.DELIVERY && rs.getDeliveryId() != null) {
                    Delivery d = deliveryMap.get(rs.getDeliveryId());
                    if (d != null && stop.getSourceDepotId().equals(d.getSourceDepotId())) {
                        parcelCount++;
                    }
                }
            }
        }

        return RouteStopFullResponse.builder()
                .id(stop.getId())
                .deliveryId(stop.getDeliveryId())
                .stopType(stop.getStopType())
                .sourceDepotId(sourceDepotId)
                .sourceDepotName(sourceDepot != null ? sourceDepot.getName() : null)
                .sourceDepotLat(sourceDepot != null ? sourceDepot.getLatitude() : null)
                .sourceDepotLng(sourceDepot != null ? sourceDepot.getLongitude() : null)
                .parcelCount(parcelCount > 0 ? parcelCount : null)
                .stopOrder(stop.getStopOrder())
                .status(resolveStopStatus(stop, delivery))
                .arrivedAt(stop.getArrivedAt())
                .completedAt(stop.getCompletedAt())
                .notes(stop.getNotes())
                .deliveryAddress(orderInfo != null ? orderInfo.getDropoffAddress() : null)
                .deliveryCity(orderInfo != null ? orderInfo.getDropoffCity() : null)
                .deliveryPostalCode(orderInfo != null ? orderInfo.getDropoffPostalCode() : null)
                .deliveryCountryCode(orderInfo != null ? orderInfo.getDropoffCountryCode() : null)
                .dropoffLat(orderInfo != null ? orderInfo.getDropoffLat() : null)
                .dropoffLng(orderInfo != null ? orderInfo.getDropoffLng() : null)
                .dropoffPinned(orderInfo != null && orderInfo.getDropoffLat() != null && orderInfo.getDropoffLng() != null)
                .routeGeometry(stop.getRouteGeometry())
                .routeDistanceKm(stop.getDriveDistanceMeters() != null ? java.math.BigDecimal.valueOf(stop.getDriveDistanceMeters() / 1000.0) : null)
                .routeDurationMinutes(stop.getDriveDurationSeconds() != null ? stop.getDriveDurationSeconds() / 60 : null)
                .routeEtaAt(stop.getEtaAt())
                .transitSlaMinutesComputed(transitSlaMinutesComputed)
                .slaPhase(slaState != null && slaState.getPhase() != null ? slaState.getPhase().name() : null)
                .slaHealth(slaState != null && slaState.getHealth() != null ? slaState.getHealth().name() : null)
                .slaStatus(delayStatus != null ? SlaStatus.valueOf(delayStatus) : null)
                .delayMinutes(delayMinutes)
                .delayStatus(delayStatus)
                .delayReason(delayReason)
                .startTimeWindow(stop.getStartTimeWindow())
                .endTimeWindow(stop.getEndTimeWindow())
                .delivery(delivery != null ? com.asm.delivery.dto.response.DeliveryResponse.builder()
                        .id(delivery.getId())
                        .orderId(orderInfo != null ? orderInfo.getId() : null)
                        .status(delivery.getStatus() != null ? delivery.getStatus().name() : null)
                        .assignedAt(delivery.getAssignedAt())
                        .pickedUpAt(delivery.getPickedUpAt())
                        .inTransitAt(delivery.getInTransitAt())
                        .completedAt(delivery.getCompletedAt())
                        .failedAt(delivery.getFailedAt())
                        .cancelledAt(delivery.getCancelledAt())
                        .failReason(delivery.getFailReason())
                        .cancelReason(delivery.getCancelReason())
                        .createdAt(delivery.getCreatedAt())
                        .build() : null)
                .order(orderInfo != null ? com.asm.delivery.dto.response.OrderResponse.builder()
                        .id(orderInfo.getId())
                        .source(orderInfo.getSource() != null ? orderInfo.getSource().name() : null)
                        .clientId(orderInfo.getClientId())
                        .clientName(orderInfo.getClientName())
                        .clientPhone(orderInfo.getClientPhone())
                        .dropoffAddress(orderInfo.getDropoffAddress())
                        .dropoffCity(orderInfo.getDropoffCity())
                        .dropoffLat(orderInfo.getDropoffLat())
                        .dropoffLng(orderInfo.getDropoffLng())
                        .deliveryInstructions(orderInfo.getDeliveryInstructions())
                        .totalAmount(orderInfo.getTotalAmount())
                        .currency(orderInfo.getCurrency())
                        .priority(orderInfo.getPriority() != null ? orderInfo.getPriority().name() : null)
                        .scheduledAt(orderInfo.getScheduledAt())
                        .items(orderInfo.getItems() != null ? new ArrayList<>(orderInfo.getItems()) : null)
                        .totalQuantity(orderInfo.getTotalQuantity())
                        .totalWeightKg(orderInfo.getTotalWeightKg())
                        .status(orderInfo.getStatus() != null ? orderInfo.getStatus().name() : null)
                        .deliveryId(delivery != null ? delivery.getId() : null)
                        .deliveryStatus(delivery != null && delivery.getStatus() != null ? delivery.getStatus().name() : null)
                        .erpOrderId(orderInfo.getErpOrderId())
                        .odooSyncStatus(orderInfo.getOdooSyncStatus())
                        .createdAt(orderInfo.getCreatedAt())
                        .updatedAt(orderInfo.getUpdatedAt())
                        .build() : null)
                .proofOfDelivery(delivery != null ? fetchDeliveryPod(delivery.getId()) : null)
                .statusHistory(delivery == null ? java.util.List.of() :
                        deliveryStatusHistoryRepository.findByDeliveryIdOrderByChangedAtAsc(delivery.getId())
                                .stream()
                                .map(h -> {
                                    String actorName = resolveActorNameLocal(h.getChangedBy(), h.getChangedByRole(), actorNames);
                                    return StatusHistoryResponse.builder()
                                        .id(h.getId() != null ? h.getId().toString() : null)
                                        .status(h.getStatus().name())
                                        .eventKey(h.getEventKey())
                                        .eventParams(deserializeEventParams(h.getEventParams()))
                                        .changedAt(h.getChangedAt())
                                        .changedBy(actorName)
                                        .actor(actorName)
                                        .changedByRole(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                                        .timestamp(h.getChangedAt())
                                        .build();
                                })
                                .toList()
                )
                .removedAt(stop.getRemovedAt())
                .removedReason(stop.getRemovedReason())
                .removedBy(stop.getRemovedBy())
                .clientName(orderInfo != null ? orderInfo.getClientName() : null)
                .orderRef(orderInfo != null ? orderInfo.resolveRef() : null)
                .build();
    }

    private String resolveActorNameLocal(String changedBy, Role role, Map<String, String> actorNames) {
        if (changedBy == null) return null;
        if ("SYSTEM".equalsIgnoreCase(changedBy)) return "Système";
        try {
            UUID.fromString(changedBy);
            if (role == Role.DRIVER) {
                return actorNames.getOrDefault(changedBy, changedBy.substring(0, 8).toUpperCase());
            }
            if (role == Role.DISPATCHER || role == Role.ADMIN) return "Dispatching";
            return changedBy.substring(0, 8).toUpperCase();
        } catch (IllegalArgumentException e) {
            return changedBy;
        }
    }

    private ProofOfDeliveryResponse fetchDeliveryPod(UUID deliveryId) {
        try {
            return proofOfDeliveryService.findPodAdmin(deliveryId).orElse(null);
        } catch (Exception ex) {
            log.warn("Skipping POD lookup for delivery {} due to error: {}", deliveryId, ex.getMessage(), ex);
            return null;
        }
    }

    private Map<String, Object> deserializeEventParams(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private RouteStopStatus resolveStopStatus(RouteStop stop, Delivery d) {
        RouteStopStatus current = stop.getStatus();
        if (current == RouteStopStatus.PENDING || current == null) {
            RouteStopStatus fallback = d != null ? mapDeliveryToRouteStopStatus(d.getStatus()) : null;
            return fallback != null ? fallback : (current != null ? current : RouteStopStatus.PENDING);
        }
        return current;
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
}
