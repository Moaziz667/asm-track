package com.asm.delivery.service;

import com.asm.delivery.dto.request.CreateRouteRequest;
import com.asm.delivery.dto.request.UpdateRouteRequest;
import com.asm.delivery.dto.response.RouteResponse;
import com.asm.delivery.dto.response.RouteStopResponse;
import com.asm.delivery.dto.response.SlaSummaryResponse;
import com.asm.delivery.entity.SlaStatus;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.entity.Vehicle;
import com.asm.delivery.entity.Order;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.odoo.OdooSyncService;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.VehicleRepository;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RouteService {

    private static final LocalTime DEFAULT_PLANNED_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_PLANNED_END = LocalTime.of(18, 0);
    private static final long MIN_ROUTE_WINDOW_MINUTES = 15;

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final VehicleRepository vehicleRepository;
    private final DeliveryRepository deliveryRepository;
    private final TransportPort transportPort;
    private final OdooSyncService odooSyncService;

    @Transactional(readOnly = true)
    public List<RouteResponse> list() {
        return routeRepository.findAllByOrderByDateDescCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<RouteResponse> list(RouteStatus status, UUID driverId, LocalDate date, String city) {
        Specification<Route> spec = Specification.where(null);

        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (driverId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("driverId"), driverId));
        }
        if (date != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("date"), date));
        }
        if (StringUtils.hasText(city)) {
            String normalizedCity = city.trim().toLowerCase();
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("city")), normalizedCity));
        }

        return routeRepository
                .findAll(spec, Sort.by(Sort.Direction.DESC, "date", "createdAt"))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RouteResponse get(UUID id) {
        return toResponse(getRoute(id));
    }

    @Transactional(readOnly = true)
    public RouteResponse getForDriver(UUID routeId, UUID driverId) {
        Route route = getRoute(routeId);
        ensureDriverOwnsRoute(route, driverId);
        return toResponse(route);
    }

    @Transactional
    public RouteResponse create(CreateRouteRequest request, String createdBy) {
        if (request.getVehicleId() != null && !vehicleRepository.existsById(request.getVehicleId())) {
            throw AppException.badRequest("Vehicle not found");
        }

        LocalTime plannedStartTime = request.getPlannedStartTime() != null
            ? request.getPlannedStartTime()
            : DEFAULT_PLANNED_START;
        LocalTime plannedEndTime = request.getPlannedEndTime() != null
            ? request.getPlannedEndTime()
            : DEFAULT_PLANNED_END;
        validateScheduleWindow(plannedStartTime, plannedEndTime);
        ensureNoScheduleConflict(request.getDriverId(), request.getDate(), plannedStartTime, plannedEndTime, null);

        Route route = Route.builder()
                .name(request.getName().trim())
                .driverId(request.getDriverId())
                .vehicleId(request.getVehicleId())
                .date(request.getDate())
                .plannedStartTime(plannedStartTime)
                .plannedEndTime(plannedEndTime)
                .city(normalizeNullableText(request.getCity()))
                .status(RouteStatus.DRAFT)
                .createdBy(StringUtils.hasText(createdBy) ? createdBy : "SYSTEM")
                .depotId(request.getDepotId())
                .departureTime(request.getDepartureTime())
                .build();

        route = routeRepository.save(route);

        if (route.getVehicleId() != null) {
            assignVehicleToDriver(route.getVehicleId(), route.getDriverId());
        }

        List<UUID> deliveryIds = request.getDeliveryIds() != null ? request.getDeliveryIds() : List.of();
        int index = 1;
        for (UUID deliveryId : deliveryIds) {
            addStopInternal(route, deliveryId, index++);
        }

        assertRouteWeightWithinVehicleCapacity(route);

        return toResponse(routeRepository.findById(route.getId()).orElse(route));
    }

    @Transactional
    public RouteResponse update(UUID id, UpdateRouteRequest request) {
        Route route = getRoute(id);
        ensureDraft(route);

        if (StringUtils.hasText(request.getName())) {
            route.setName(request.getName().trim());
        }
        if (request.getDriverId() != null) {
            route.setDriverId(request.getDriverId());
        }
        if (request.getVehicleId() != null) {
            if (!vehicleRepository.existsById(request.getVehicleId())) {
                throw AppException.badRequest("Vehicle not found");
            }
            route.setVehicleId(request.getVehicleId());
        }
        if (request.getDate() != null) {
            route.setDate(request.getDate());
        }
        if (request.getPlannedStartTime() != null) {
            route.setPlannedStartTime(request.getPlannedStartTime());
        }
        if (request.getPlannedEndTime() != null) {
            route.setPlannedEndTime(request.getPlannedEndTime());
        }
        if (request.getCity() != null) {
            route.setCity(normalizeNullableText(request.getCity()));
        }
        if (request.getDepotId() != null) {
            route.setDepotId(request.getDepotId());
        }
        if (request.getDepartureTime() != null) {
            route.setDepartureTime(request.getDepartureTime());
        }

        LocalTime effectiveStart = route.getPlannedStartTime() != null ? route.getPlannedStartTime() : DEFAULT_PLANNED_START;
        LocalTime effectiveEnd = route.getPlannedEndTime() != null ? route.getPlannedEndTime() : DEFAULT_PLANNED_END;
        route.setPlannedStartTime(effectiveStart);
        route.setPlannedEndTime(effectiveEnd);
        validateScheduleWindow(effectiveStart, effectiveEnd);
        ensureNoScheduleConflict(route.getDriverId(), route.getDate(), effectiveStart, effectiveEnd, route.getId());

        if (route.getVehicleId() != null) {
            assignVehicleToDriver(route.getVehicleId(), route.getDriverId());
        }

        assertRouteWeightWithinVehicleCapacity(route);

        return toResponse(routeRepository.save(route));
    }

    private void assignVehicleToDriver(UUID vehicleId, UUID driverId) {
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> AppException.badRequest("Vehicle not found"));
        vehicle.setDriverId(driverId);
        vehicleRepository.save(vehicle);
    }

    @Transactional
    public void delete(UUID id) {
        Route route = getRoute(id);
        ensureDraft(route);
        routeRepository.delete(route);
    }

    @Transactional
    public RouteResponse addStop(UUID routeId, UUID deliveryId) {
        Route route = getRoute(routeId);
        ensureDraft(route);
        int nextOrder = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId).size() + 1;
        addStopInternal(route, deliveryId, nextOrder);
        assertRouteWeightWithinVehicleCapacity(route);
        return toResponse(route);
    }

    @Transactional
    public RouteResponse removeStop(UUID routeId, UUID stopId) {
        Route route = getRoute(routeId);
        ensureDraft(route);

        RouteStop stop = routeStopRepository.findByRouteIdAndId(routeId, stopId)
                .orElseThrow(() -> AppException.notFound("Route stop not found"));
        routeStopRepository.delete(stop);

        // Re-pack stop order after deletion
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        for (int i = 0; i < stops.size(); i++) {
            stops.get(i).setStopOrder(i + 1);
        }

        return toResponse(route);
    }

    @Transactional
    public RouteResponse reorderStops(UUID routeId, List<UUID> stopIds) {
        Route route = getRoute(routeId);
        ensureDraft(route);

        List<RouteStop> existing = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        if (existing.size() != stopIds.size()) {
            throw AppException.badRequest("Reorder payload does not match current stop count");
        }

        Map<UUID, RouteStop> byId = existing.stream().collect(Collectors.toMap(RouteStop::getId, Function.identity()));
        for (int i = 0; i < stopIds.size(); i++) {
            RouteStop stop = byId.get(stopIds.get(i));
            if (stop == null) {
                throw AppException.badRequest("Stop id does not belong to this route");
            }
            stop.setStopOrder(i + 1);
        }

        return toResponse(route);
    }

    @Transactional
    public RouteResponse validate(UUID routeId) {
        Route route = getRoute(routeId);
        ensureDraft(route);

        assertRouteWeightWithinVehicleCapacity(route);

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        if (stops.isEmpty()) {
            throw AppException.badRequest("Cannot validate route without stops");
        }

        List<UUID> deliveryIds = stops.stream().map(RouteStop::getDeliveryId).toList();
        Map<UUID, Delivery> deliveryMap = deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                .collect(Collectors.toMap(Delivery::getId, Function.identity()));

        for (RouteStop stop : stops) {
            Delivery delivery = deliveryMap.get(stop.getDeliveryId());
            if (delivery == null) {
                throw AppException.badRequest("Delivery not found for stop: " + stop.getDeliveryId());
            }

            Order order = delivery.getOrder();
            if (order == null || order.getDropoffLat() == null || order.getDropoffLng() == null) {
                throw AppException.badRequest("Stop " + stop.getStopOrder() + " is not pinned yet. Pin all stop addresses before validating the route.");
            }

            if (delivery.getStatus() == DeliveryStatus.WAITING_DRIVER) {
                delivery.setDriverId(route.getDriverId());
                delivery.setStatus(DeliveryStatus.ASSIGNED);
                delivery.setAssignedAt(LocalDateTime.now());
                deliveryRepository.save(delivery);
            }
        }

        route.setStatus(RouteStatus.VALIDATED);
        route.setValidatedAt(LocalDateTime.now());
        transportPort.setAvailability(route.getDriverId().toString(), false);
        return toResponse(routeRepository.save(route));
    }

    @Transactional
    public RouteResponse close(UUID routeId) {
        Route route = getRoute(routeId);
        if (route.getStatus() != RouteStatus.IN_PROGRESS) {
            throw AppException.badRequest("Admin can close only in-progress routes after driver termination");
        }

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        if (stops.isEmpty()) {
            throw AppException.badRequest("Cannot close route without stops");
        }
        boolean allTerminal = stops.stream().allMatch(s -> isTerminalStopStatus(s.getStatus()));
        if (!allTerminal) {
            throw AppException.badRequest("Admin can close only after driver has terminated all stops");
        }

        route.setStatus(RouteStatus.CLOSED);
        route.setClosedAt(LocalDateTime.now());
        transportPort.setAvailability(route.getDriverId().toString(), true);
        return toResponse(routeRepository.save(route));
    }

    @Transactional(readOnly = true)
    public RouteResponse getTodayForDriver(UUID driverId) {
        List<RouteStatus> statuses = List.of(RouteStatus.VALIDATED, RouteStatus.IN_PROGRESS);
        Route route = routeRepository.findByDriverIdAndDateAndStatusIn(driverId, LocalDate.now(), statuses)
                .stream()
                .findFirst()
                .orElseThrow(() -> AppException.notFound("No route assigned for today"));
        return toResponse(route);
    }

    @Transactional
    public RouteResponse start(UUID routeId, UUID driverId) {
        Route route = getRoute(routeId);
        ensureDriverOwnsRoute(route, driverId);

        if (route.getStatus() != RouteStatus.VALIDATED) {
            throw AppException.badRequest("Only validated routes can be started");
        }

        route.setStatus(RouteStatus.IN_PROGRESS);
        return toResponse(routeRepository.save(route));
    }

    @Transactional
    public RouteResponse arrive(UUID routeId, UUID stopId, UUID driverId) {
        Route route = getRoute(routeId);
        ensureDriverOwnsRoute(route, driverId);

        if (route.getStatus() != RouteStatus.IN_PROGRESS && route.getStatus() != RouteStatus.VALIDATED) {
            throw AppException.badRequest("Route is not active");
        }

        RouteStop stop = routeStopRepository.findByRouteIdAndId(routeId, stopId)
                .orElseThrow(() -> AppException.notFound("Route stop not found"));

        stop.setStatus(RouteStopStatus.ARRIVED);
        stop.setArrivedAt(LocalDateTime.now());
        routeStopRepository.save(stop);

        if (route.getStatus() == RouteStatus.VALIDATED) {
            route.setStatus(RouteStatus.IN_PROGRESS);
            routeRepository.save(route);
        }

        return toResponse(route);
    }

    @Transactional
    public void syncStopFromDelivery(UUID deliveryId, DeliveryStatus deliveryStatus, LocalDateTime eventAt, String note) {
        routeStopRepository.findByDeliveryId(deliveryId).ifPresent(stop -> {
            RouteStopStatus mappedStatus = mapDeliveryToRouteStopStatus(deliveryStatus);
            if (mappedStatus == null) {
                return;
            }

            stop.setStatus(mappedStatus);
            if (isTerminalStopStatus(mappedStatus)) {
                stop.setCompletedAt(eventAt != null ? eventAt : LocalDateTime.now());
            }
            if (StringUtils.hasText(note)) {
                stop.setNotes(note.trim());
            }
            routeStopRepository.save(stop);

            Route route = stop.getRoute();
            if (route == null) {
                return;
            }

            if (route.getStatus() == RouteStatus.VALIDATED) {
                route.setStatus(RouteStatus.IN_PROGRESS);
                routeRepository.save(route);
            }

            maybeAutoCloseRoute(route);
        });
    }

    private void addStopInternal(Route route, UUID deliveryId, int stopOrder) {
        if (routeStopRepository.existsByDeliveryId(deliveryId)) {
            throw AppException.conflict("Delivery already attached to another route");
        }

        if (!deliveryRepository.existsById(deliveryId)) {
            throw AppException.badRequest("Delivery not found");
        }

        RouteStop stop = RouteStop.builder()
                .route(route)
                .deliveryId(deliveryId)
                .stopOrder(stopOrder)
                .status(RouteStopStatus.PENDING)
                .build();

        routeStopRepository.save(stop);
    }

    private void assertRouteWeightWithinVehicleCapacity(Route route) {
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        if (stops.isEmpty()) {
            return;
        }

        if (route.getVehicleId() == null) {
            throw AppException.badRequest("Route vehicle is required before this operation");
        }

        Vehicle vehicle = vehicleRepository.findById(route.getVehicleId())
                .orElseThrow(() -> AppException.badRequest("Vehicle not found"));

        Integer payloadKg = vehicle.getPayloadKg();
        if (payloadKg == null || payloadKg < 1) {
            throw AppException.badRequest("Selected vehicle has no valid max payload configured");
        }

        List<UUID> deliveryIds = stops.stream().map(RouteStop::getDeliveryId).toList();
        Map<UUID, Delivery> deliveryMap = deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                .collect(Collectors.toMap(Delivery::getId, Function.identity()));

        BigDecimal totalWeightKg = BigDecimal.ZERO;
        for (RouteStop stop : stops) {
            Delivery delivery = deliveryMap.get(stop.getDeliveryId());
            if (delivery == null || delivery.getOrder() == null) {
                continue;
            }
            BigDecimal orderWeight = delivery.getOrder().getTotalWeightKg();
            if (orderWeight != null) {
                totalWeightKg = totalWeightKg.add(orderWeight);
            }
        }

        BigDecimal vehicleCapacity = BigDecimal.valueOf(payloadKg);
        if (totalWeightKg.compareTo(vehicleCapacity) > 0) {
            BigDecimal excess = totalWeightKg.subtract(vehicleCapacity);
            throw AppException.badRequest(
                    "Route load exceeds vehicle capacity by " + excess.stripTrailingZeros().toPlainString()
                            + " kg (load=" + totalWeightKg.stripTrailingZeros().toPlainString()
                            + " kg, capacity=" + vehicleCapacity.stripTrailingZeros().toPlainString() + " kg)"
            );
        }
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

    private static String normalizeNullableText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static RouteStopStatus mapDeliveryToRouteStopStatus(DeliveryStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
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

    private void maybeAutoCloseRoute(Route route) {
        if (route.getStatus() != RouteStatus.VALIDATED && route.getStatus() != RouteStatus.IN_PROGRESS) {
            return;
        }

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        if (stops.isEmpty()) {
            return;
        }

        boolean allTerminal = stops.stream().allMatch(s -> isTerminalStopStatus(s.getStatus()));
        if (!allTerminal) {
            return;
        }

        route.setStatus(RouteStatus.CLOSED);
        route.setClosedAt(LocalDateTime.now());
        routeRepository.save(route);
        transportPort.setAvailability(route.getDriverId().toString(), true);
    }

    private static void validateScheduleWindow(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null) {
            throw AppException.badRequest("Route schedule window is required");
        }
        if (!startTime.isBefore(endTime)) {
            throw AppException.badRequest("Route start time must be before end time");
        }
        long minutes = ChronoUnit.MINUTES.between(startTime, endTime);
        if (minutes < MIN_ROUTE_WINDOW_MINUTES) {
            throw AppException.badRequest("Route schedule window must be at least 15 minutes");
        }
    }

    private void ensureNoScheduleConflict(UUID driverId,
                                          LocalDate date,
                                          LocalTime startTime,
                                          LocalTime endTime,
                                          UUID currentRouteId) {
        List<Route> sameDayRoutes = routeRepository.findAllByDriverIdAndDate(driverId, date);
        for (Route existing : sameDayRoutes) {
            if (existing.getStatus() == RouteStatus.CLOSED) {
                continue;
            }
            if (currentRouteId != null && existing.getId().equals(currentRouteId)) {
                continue;
            }

            LocalTime existingStart = existing.getPlannedStartTime() != null ? existing.getPlannedStartTime() : DEFAULT_PLANNED_START;
            LocalTime existingEnd = existing.getPlannedEndTime() != null ? existing.getPlannedEndTime() : DEFAULT_PLANNED_END;
            if (startTime.isBefore(existingEnd) && existingStart.isBefore(endTime)) {
                throw AppException.conflict("Driver already has an overlapping route for this time window");
            }
        }
    }

    private RouteResponse toResponse(Route route) {
        List<RouteStop> routeStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());

        Map<UUID, Delivery> deliveriesById = new HashMap<>();
        List<UUID> deliveryIds = routeStops.stream().map(RouteStop::getDeliveryId).toList();
        if (!deliveryIds.isEmpty()) {
            deliveriesById = deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                    .collect(Collectors.toMap(Delivery::getId, Function.identity()));
        }

        List<RouteStopResponse> stops = new ArrayList<>();
        for (RouteStop stop : routeStops) {
            Delivery delivery = deliveriesById.get(stop.getDeliveryId());
            Order order = delivery != null ? delivery.getOrder() : null;
            boolean isPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;
            stops.add(RouteStopResponse.builder()
                    .id(stop.getId())
                    .deliveryId(stop.getDeliveryId())
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
                    .routeGeometry(delivery != null ? delivery.getRouteGeometry() : null)
                    .routeDistanceKm(delivery != null ? delivery.getRouteDistanceKm() : null)
                    .routeDurationMinutes(delivery != null ? delivery.getRouteDurationMinutes() : null)
                    .routeEtaAt(delivery != null ? delivery.getRouteEtaAt() : null)
                    .transitSlaMinutesComputed(delivery != null ? delivery.getTransitSlaMinutesComputed() : null)
                    .routeProvider(delivery != null ? delivery.getRouteProvider() : null)
                    .etaAt(stop.getEtaAt())
                    .slaDeadline(stop.getSlaDeadline())
                    .slaStatus(stop.getSlaStatus())
                    .driveDurationSeconds(stop.getDriveDurationSeconds())
                    .driveDistanceMeters(stop.getDriveDistanceMeters())
                    .actualArrivalAt(stop.getActualArrivalAt())
                    .dwellMinutes(stop.getDwellMinutes())
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

        return RouteResponse.builder()
                .id(route.getId())
                .name(route.getName())
                .driverId(route.getDriverId())
                .vehicleId(route.getVehicleId())
                .date(route.getDate())
                .plannedStartTime(route.getPlannedStartTime())
                .plannedEndTime(route.getPlannedEndTime())
                    .city(route.getCity())
                .status(route.getStatus())
                .createdBy(route.getCreatedBy())
                .createdAt(route.getCreatedAt())
                .validatedAt(route.getValidatedAt())
                .closedAt(route.getClosedAt())
                    .totalStops(totalStops)
                    .completedStops(completedStops)
                    .failedStops(failedStops)
                    .partialStops(partialStops)
                    .pendingStops(pendingStops)
                    .progressPercent(progressPercent)
                    .etaDriftMinutes(etaDriftMinutes)
                .stops(stops)
                .depotId(route.getDepotId())
                .departureTime(route.getDepartureTime())
                .totalDurationSeconds(route.getTotalDurationSeconds())
                .totalDistanceMeters(route.getTotalDistanceMeters())
                .isOptimized(route.getIsOptimized())
                .routeGeometry(route.getRouteGeometry())
                .build();
    }

    // ─── SLA Summary ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SlaSummaryResponse getSlaSummary() {
        List<RouteStop> stops = routeStopRepository.findActivePendingStopsWithSla();

        int onTime = 0, atRisk = 0, breached = 0;
        List<RouteStop> atRiskList = new ArrayList<>();
        List<RouteStop> breachedList = new ArrayList<>();

        for (RouteStop stop : stops) {
            if (stop.getSlaStatus() == SlaStatus.ON_TIME)       onTime++;
            else if (stop.getSlaStatus() == SlaStatus.AT_RISK)  { atRisk++;   atRiskList.add(stop); }
            else if (stop.getSlaStatus() == SlaStatus.BREACHED) { breached++; breachedList.add(stop); }
        }

        // Load client names for urgent stops only
        List<UUID> urgentIds = new ArrayList<>();
        atRiskList.stream().map(RouteStop::getDeliveryId).forEach(urgentIds::add);
        breachedList.stream().map(RouteStop::getDeliveryId).forEach(urgentIds::add);

        Map<UUID, Delivery> deliveryMap = urgentIds.isEmpty()
                ? Collections.emptyMap()
                : deliveryRepository.findAllByIdInWithOrder(urgentIds).stream()
                        .collect(Collectors.toMap(Delivery::getId, Function.identity()));

        return SlaSummaryResponse.builder()
                .onTime(onTime)
                .atRisk(atRisk)
                .breached(breached)
                .total(onTime + atRisk + breached)
                .atRiskStops(toSlaItems(atRiskList.stream().limit(10).toList(), deliveryMap))
                .breachedStops(toSlaItems(breachedList.stream().limit(10).toList(), deliveryMap))
                .build();
    }

    private List<SlaSummaryResponse.SlaStopItem> toSlaItems(List<RouteStop> stops, Map<UUID, Delivery> deliveryMap) {
        return stops.stream().map(stop -> {
            Delivery d = deliveryMap.get(stop.getDeliveryId());
            String clientName = (d != null && d.getOrder() != null) ? d.getOrder().getClientName() : null;
            return SlaSummaryResponse.SlaStopItem.builder()
                    .stopId(stop.getId())
                    .deliveryId(stop.getDeliveryId())
                    .routeId(stop.getRoute().getId())
                    .clientName(clientName)
                    .etaAt(stop.getEtaAt())
                    .slaDeadline(stop.getSlaDeadline())
                    .slaStatus(stop.getSlaStatus().name())
                    .build();
        }).toList();
    }
}
