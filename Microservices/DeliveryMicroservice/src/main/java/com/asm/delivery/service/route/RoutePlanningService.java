package com.asm.delivery.service.route;

import com.asm.delivery.dto.request.CreateRouteRequest;
import com.asm.delivery.dto.request.UpdateRouteRequest;
import com.asm.delivery.dto.response.*;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.*;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.service.ProofOfDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutePlanningService {

    private static final LocalTime DEFAULT_PLANNED_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_PLANNED_END = LocalTime.of(18, 0);

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final VehicleRepository vehicleRepository;
    private final DeliveryRepository deliveryRepository;
    private final TransportPort transportPort;
    private final ZoneRepository zoneRepository;
    private final DepotRepository depotRepository;
    private final DelayCalculationService delayCalculationService;
    private final AuditLogService auditLogService;
    private final ProofOfDeliveryService proofOfDeliveryService;
    private final DeliveryStatusHistoryRepository deliveryStatusHistoryRepository;

    public void deleteByRouteId(UUID routeId) {
        routeStopRepository.deleteByRouteId(routeId);
    }
    @Transactional(readOnly = true)
    public List<RouteResponse> list() {
        return routeRepository.findAllByOrderByDateDescCreatedAtDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<RouteResponse> list(RouteStatus status, UUID driverId, LocalDate date, LocalDate from, LocalDate to, String city) {
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

        if (from != null && to != null) {
            spec = spec.and((root, query, cb) -> cb.between(root.get("date"), from, to));
        } else if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("date"), from));
        } else if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("date"), to));
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
    public RouteFullResponse getRouteFull(UUID id) {
        try {
            Route route = getRoute(id);
            return toFullResponse(route);
        } catch (Exception ex) {
            log.error("Failed to load full route {}", id, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public RouteResponse getForDriver(UUID routeId, UUID driverId) {
        Route route = getRoute(routeId);
        ensureDriverOwnsRoute(route, driverId);
        return toResponse(route);
    }

    @Transactional
    public RouteResponse create(CreateRouteRequest request, String createdBy) {
        if (request.getDepotId() == null) {
            throw AppException.badRequest("Depot is required — ETA and route optimization depend on it");
        }
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

        auditLogService.logAction(null, "CREATE_ROUTE", "ROUTE", route.getId().toString(),
                Map.of("tournee", route.getName(), "action", "Creation de tournee"));

        if (route.getVehicleId() != null) {
            assignVehicleToDriver(route.getVehicleId(), route.getDriverId());
        }

        // Validate and create stops
        if (request.getStopConfigs() != null && !request.getStopConfigs().isEmpty()) {
            validateStopChronology(request.getStopConfigs(), plannedStartTime);
            int index = 1;
            for (CreateRouteRequest.StopConfig config : request.getStopConfigs()) {
                addStopInternal(route, config.getDeliveryId(), index++, 
                    config.getStartTimeWindow(), config.getEndTimeWindow(), config.getBufferMinutes());
            }
        } else if (request.getDeliveryIds() != null) {
            int index = 1;
            for (UUID deliveryId : request.getDeliveryIds()) {
                addStopInternal(route, deliveryId, index++, null, null, 30);
            }
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
            route.setPlannedStartTime(LocalTime.parse(request.getPlannedStartTime()));
        }
        if (request.getPlannedEndTime() != null) {
            route.setPlannedEndTime(LocalTime.parse(request.getPlannedEndTime()));
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

        // --- UPDATE STOPS IF PROVIDED ---
        if (request.getStopConfigs() != null && !request.getStopConfigs().isEmpty()) {
            validateUpdateStopChronology(request.getStopConfigs(), effectiveStart);

            this.deleteByRouteId(route.getId());
            routeStopRepository.flush();

            int index = 1;
            for (UpdateRouteRequest.StopConfig config : request.getStopConfigs()) {
                addStopInternal(
                        route,
                        config.getDeliveryId(),
                        index++,
                        LocalTime.parse(config.getStartTimeWindow()),
                        LocalTime.parse(config.getEndTimeWindow()),
                        config.getBufferMinutes()
                );
            }
        } else if (request.getDeliveryIds() != null) {
            this.deleteByRouteId(route.getId());
            routeStopRepository.flush();

            int index = 1;
            for (UUID deliveryId : request.getDeliveryIds()) {
                addStopInternal(route, deliveryId, index++, null, null, 30);
            }
        }

        assertRouteWeightWithinVehicleCapacity(route);

        auditLogService.logAction(null, "UPDATE_ROUTE", "ROUTE", route.getId().toString(),
                Map.of("tournee", route.getName(), "action", "Mise a jour de tournee"));

        return toResponse(routeRepository.save(route));
    }

    private void validateUpdateStopChronology(List<UpdateRouteRequest.StopConfig> configs, LocalTime routeStart) {
        LocalTime lastEnd = routeStart;
        int i = 1;
        for (UpdateRouteRequest.StopConfig config : configs) {
            String startStr = config.getStartTimeWindow();
            String endStr = config.getEndTimeWindow();

            if (!StringUtils.hasText(startStr) || !StringUtils.hasText(endStr)) {
                throw AppException.badRequest("Stop #" + i + ": Start/End time windows are required");
            }

            LocalTime start;
            LocalTime end;
            try {
                start = LocalTime.parse(startStr);
                end = LocalTime.parse(endStr);
            } catch (DateTimeParseException ex) {
                throw AppException.badRequest("Stop #" + i + ": Invalid time format, expected HH:mm[:ss]");
            }

            if (start.isBefore(lastEnd)) {
                throw AppException.badRequest("Stop #" + i + ": Start time (" + start + ") is before previous stop ends (" + lastEnd + ")");
            }
            if (!start.isBefore(end)) {
                throw AppException.badRequest("Stop #" + i + ": End time must be after start time");
            }
            lastEnd = end;
            i++;
        }
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
        auditLogService.logAction(null, "DELETE_ROUTE", "ROUTE", id.toString(),
                Map.of("tournee", route.getName(), "action", "Suppression de tournee"));
    }

    @Transactional
    public RouteResponse addStop(UUID routeId, com.asm.delivery.dto.request.AddRouteStopRequest request) {
        Route route = getRoute(routeId);
        ensureDraft(route);
        int nextOrder = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId).size() + 1;
        addStopInternal(route, request.getDeliveryId(), nextOrder, request.getStartTimeWindow(), request.getEndTimeWindow(), request.getBufferMinutes());
        assertRouteWeightWithinVehicleCapacity(route);
        auditLogService.logAction(null, "ADD_STOP", "ROUTE", routeId.toString(),
                Map.of("tournee", route.getName(), "action", "Ajout d'un arret"));
        return toResponse(route);
    }

    @Transactional
    public RouteResponse removeStop(UUID routeId, UUID stopId) {
        Route route = getRoute(routeId);
        ensureDraft(route);

        RouteStop stop = routeStopRepository.findByRouteIdAndId(routeId, stopId)
                .orElseThrow(() -> AppException.notFound("Route stop not found"));
        routeStopRepository.delete(stop);
        auditLogService.logAction(null, "REMOVE_STOP", "ROUTE", routeId.toString(),
                Map.of("tournee", route.getName(), "action", "Suppression d'un arret"));

        // Re-pack stop order after deletion
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        for (int i = 0; i < stops.size(); i++) {
            stops.get(i).setStopOrder(i + 1);
        }
        routeStopRepository.saveAll(stops);

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
        routeStopRepository.saveAll(new java.util.ArrayList<>(byId.values()));

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

            if (delivery.getStatus() == DeliveryStatus.UNSCHEDULED) {
                delivery.setDriverId(route.getDriverId());
                delivery.setStatus(DeliveryStatus.SCHEDULED);
                delivery.setAssignedAt(LocalDateTime.now());
                delivery.setWaitingSlaMinutes(delayCalculationService.calculateWaitingSlaMinutes(delivery));
                deliveryRepository.save(delivery);
                appendHistory(delivery, DeliveryStatus.SCHEDULED, "SYSTEM", Role.SYSTEM, "Route validated and delivery assigned");
                routeStopRepository.findByDeliveryId(delivery.getId()).ifPresent(routeStop -> {
                    routeStop.setStatus(RouteStopStatus.SCHEDULED);
                    routeStopRepository.save(routeStop);
                });
            }
        }

        // Multi-zone detection: collect all distinct zone IDs from stop postal codes/cities
        List<String> validationWarnings = new ArrayList<>();
        {
            List<String> postalCodes = stops.stream()
                    .map(s -> deliveryMap.get(s.getDeliveryId()))
                    .filter(d -> d != null && d.getOrder() != null)
                    .map(d -> d.getOrder().getDropoffPostalCode())
                    .filter(pc -> pc != null && !pc.isBlank())
                    .distinct()
                    .toList();

            List<Zone> detectedZones = postalCodes.isEmpty()
                    ? List.of()
                    : zoneRepository.findActiveZonesByPostalCodes(postalCodes.toArray(new String[0]));

            if (detectedZones.size() > 1) {
                String label = detectedZones.stream().map(Zone::getName).collect(Collectors.joining(" · "));
                validationWarnings.add("Route crosses multiple zones: " + label + " — confirm this is intentional");
            }
        }

        route.setStatus(RouteStatus.VALIDATED);
        route.setValidatedAt(LocalDateTime.now());
        RouteResponse response = toResponse(routeRepository.save(route));
        if (!validationWarnings.isEmpty()) {
            response.setValidationWarnings(validationWarnings);
        }
        return response;
    }
    private void addStopInternal(Route route, UUID deliveryId, int stopOrder, LocalTime start, LocalTime end, Integer buffer) {
        routeStopRepository.findByDeliveryId(deliveryId).ifPresent(existingStop -> {
            UUID existingRouteId = existingStop.getRoute() != null ? existingStop.getRoute().getId() : null;
            UUID currentRouteId = route.getId();
            if (existingRouteId == null || !existingRouteId.equals(currentRouteId)) {
                String existingRouteName = existingStop.getRoute() != null ? existingStop.getRoute().getName() : null;
                String message = existingRouteName != null
                        ? "Delivery already attached to route: " + existingRouteName
                        : "Delivery already attached to another route";
                throw AppException.conflict(message);
            }
        });

        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> AppException.badRequest("Delivery not found"));

        // Enforce pin-before-route rule
        Order order = delivery.getOrder();
        if (order == null || order.getDropoffLat() == null || order.getDropoffLng() == null) {
            String ref = order != null && org.springframework.util.StringUtils.hasText(order.getErpOrderId())
                    ? order.getErpOrderId() : deliveryId.toString();
            throw AppException.badRequest("Order " + ref + " has no pin — pin the dropoff address before adding to a route");
        }

        RouteStop stop = RouteStop.builder()
                .route(route)
                .deliveryId(deliveryId)
                .stopOrder(stopOrder)
                .status(RouteStopStatus.PENDING)
                .startTimeWindow(start)
                .endTimeWindow(end)
                .bufferMinutes(buffer != null ? buffer : 30)
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
    }
    private static void validateScheduleWindow(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null) {
            throw AppException.badRequest("Route schedule window is required");
        }
        if (!startTime.isBefore(endTime)) {
            throw AppException.badRequest("Route start time must be before end time");
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

    private void validateStopChronology(List<CreateRouteRequest.StopConfig> configs, LocalTime routeStart) {
        LocalTime lastEnd = routeStart;
        int i = 1;
        for (CreateRouteRequest.StopConfig config : configs) {
            LocalTime start = config.getStartTimeWindow();
            LocalTime end = config.getEndTimeWindow();

            if (start == null || end == null) {
                throw AppException.badRequest("Stop #" + i + ": Start/End time windows are required");
            }
            if (start.isBefore(lastEnd)) {
                throw AppException.badRequest("Stop #" + i + ": Start time (" + start + ") is before previous stop ends (" + lastEnd + ")");
            }
            if (!start.isBefore(end)) {
                throw AppException.badRequest("Stop #" + i + ": End time must be after start time");
            }
            lastEnd = end;
            i++;
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

        // Separate active and legacy stops
        List<RouteStop> activeStops = routeStops.stream()
                .filter(s -> s.getStatus() != RouteStopStatus.REMOVED)
                .toList();
        List<RouteStop> legacyStops = routeStops.stream()
                .filter(s -> s.getStatus() == RouteStopStatus.REMOVED)
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
                    : stop.getSlaStatus();

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
                    .routeGeometry(stop.getRouteGeometry())
                    .routeDistanceKm(delivery != null ? delivery.getRouteDistanceKm() : null)
                    .routeDurationMinutes(delivery != null ? delivery.getRouteDurationMinutes() : null)
                    .routeEtaAt(delivery != null ? delivery.getRouteEtaAt() : null)
                    .transitSlaMinutesComputed(delivery != null ? delivery.getTransitSlaMinutesComputed() : null)
                    .routeProvider(delivery != null ? delivery.getRouteProvider() : null)
                    .etaAt(stop.getEtaAt())
                    .slaDeadline(stop.getSlaDeadline())
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
                    .orderRef(order != null ? (order.getErpOrderId() != null ? order.getErpOrderId() : order.getErpExternalRef()) : null)
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
                : zoneRepository.findActiveZonesByPostalCodes(postalCodes.toArray(new String[0]));
        List<String> detectedZoneNames = detectedZones.stream().map(Zone::getName).toList();
        String detectedZoneLabel = detectedZoneNames.isEmpty()
                ? ""
                : String.join(" · ", detectedZoneNames);

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
                .departureTime(route.getDepartureTime())
                .totalDurationSeconds(route.getTotalDurationSeconds())
                .totalDistanceMeters(route.getTotalDistanceMeters())
                .isOptimized(route.getIsOptimized())
                .routeGeometry(route.getRouteGeometry())
                .detectedZoneLabel(detectedZoneLabel)
                .detectedZoneNames(detectedZoneNames)
                .routeStartDelayMinutes(routeStartDelayMinutes)
                .legacyStops(legacyStopResponses.isEmpty() ? null : legacyStopResponses)
                .build();
    }

    private RouteFullResponse toFullResponse(Route route) {
        // --- Same initial logic as toResponse ---
        // Load stops from repository (guarantees stopOrder ASC, consistent with toResponse)
        List<RouteStop> allStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        List<RouteStop> activeStops = allStops.stream()
                .filter(stop -> stop.getStatus() != RouteStopStatus.REMOVED)
                .toList();

        List<RouteStopFullResponse> stops = activeStops.stream()
                .map(stop -> toFullStopResponse(stop, route, activeStops)) // Pass activeStops to calculate delay properly
                .toList();

        List<RouteStopFullResponse> legacyStops = allStops.stream()
                .filter(stop -> stop.getStatus() == RouteStopStatus.REMOVED)
                .map(stop -> toFullStopResponse(stop, route, activeStops))
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
                .map(s -> s.getDeliveryId().toString())
                .toList();

        String detectedZoneLabel = null;
        List<String> detectedZoneNames = new java.util.ArrayList<>();
        if (route.getZoneId() != null) {
            com.asm.delivery.entity.Zone z = zoneRepository.findById(route.getZoneId()).orElse(null);
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
                .driver(buildDriverResponse(route.getDriverId()))
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
                .build();
    }

    private AdminDriverResponse buildDriverResponse(UUID driverId) {
        if (driverId == null) return null;
        com.asm.delivery.transport.DriverDTO dto = transportPort.getDriver(driverId.toString());
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

    private RouteStopFullResponse toFullStopResponse(RouteStop stop, Route route, List<RouteStop> activeStops) {
        Delivery delivery = deliveryRepository.findById(stop.getDeliveryId())
                .orElseThrow(() -> new com.asm.delivery.exception.AppException(org.springframework.http.HttpStatus.NO_CONTENT, "Delivery not found for stop " + stop.getId()));

        com.asm.delivery.entity.Order orderInfo = delivery.getOrder();
        
        // Calculate delay details
        DelayCalculationService.DelayInfo delayInfo = delayCalculationService.calculateDelay(stop, route, activeStops);
        Integer delayMinutes = delayInfo != null ? delayInfo.delayMinutes : null;
        String delayStatus = delayInfo != null ? delayInfo.delayStatus : null;
        String delayReason = delayInfo != null ? delayInfo.delayReason : null;
        Integer transitSlaMinutesComputed = null; // Removed as it is not in DelayInfo


        return RouteStopFullResponse.builder()
                .id(stop.getId())
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
                .slaStatus(delayStatus != null ? SlaStatus.valueOf(delayStatus) : stop.getSlaStatus())
                .delayMinutes(delayMinutes)
                .delayStatus(delayStatus)
                .delayReason(delayReason)
                .startTimeWindow(stop.getStartTimeWindow())
                .endTimeWindow(stop.getEndTimeWindow())
                .delivery(com.asm.delivery.dto.response.DeliveryResponse.builder()
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
                        .build())
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
                        .deliveryId(delivery.getId())
                        .deliveryStatus(delivery.getStatus() != null ? delivery.getStatus().name() : null)
                        .erpOrderId(orderInfo.getErpOrderId())
                        .odooSyncStatus(orderInfo.getOdooSyncStatus())
                        .createdAt(orderInfo.getCreatedAt())
                        .updatedAt(orderInfo.getUpdatedAt())
                        .build() : null)
                .proofOfDelivery(fetchDeliveryPod(delivery.getId()))
                .statusHistory(
                        deliveryStatusHistoryRepository.findByDeliveryIdOrderByChangedAtAsc(delivery.getId())
                                .stream()
                                .map(h -> StatusHistoryResponse.builder()
                                        .id(h.getId() != null ? h.getId().toString() : null)
                                        .status(h.getStatus().name())
                                        .note(h.getNote())
                                        .changedAt(h.getChangedAt())
                                        .changedBy(h.getChangedBy())
                                        .build())
                                .toList()
                )
                .build();
    }

    private ProofOfDeliveryResponse fetchDeliveryPod(UUID deliveryId) {
        try {
            return proofOfDeliveryService.findPodAdmin(deliveryId).orElse(null);
        } catch (Exception ex) {
            log.warn("Skipping POD lookup for delivery {} due to error: {}", deliveryId, ex.getMessage(), ex);
            return null;
        }
    }

    // ─── SLA Summary ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SlaSummaryResponse getSlaSummary() {
        List<RouteStop> stops = routeStopRepository.findActivePendingStops();
        int total = stops.size();
        int late = (int) stops.stream().filter(s -> s.getSlaStatus() == SlaStatus.LATE).count();
        int onTime = (int) stops.stream().filter(s -> s.getSlaStatus() == SlaStatus.ON_TIME || s.getSlaStatus() == SlaStatus.EARLY).count();

        List<SlaSummaryResponse.SlaStopItem> lateStops = stops.stream()
            .filter(s -> s.getSlaStatus() == SlaStatus.LATE)
            .sorted((a, b) -> {
                if (a.getEtaAt() == null && b.getEtaAt() == null) return 0;
                if (a.getEtaAt() == null) return 1;
                if (b.getEtaAt() == null) return -1;
                return a.getEtaAt().compareTo(b.getEtaAt());
            })
            .limit(10)
            .map(s -> SlaSummaryResponse.SlaStopItem.builder()
                .stopId(s.getId())
                .deliveryId(s.getDeliveryId())
                .routeId(s.getRoute() != null ? s.getRoute().getId() : null)
                .clientName(null)
                .etaAt(s.getEtaAt())
                .slaDeadline(s.getSlaDeadline())
                .slaStatus(s.getSlaStatus() != null ? s.getSlaStatus().name() : null)
                .build())
            .collect(Collectors.toList());

        return SlaSummaryResponse.builder()
            .onTime(onTime)
            .late(late)
            .total(total)
            .lateStops(lateStops)
            .build();
    }

    private void appendHistory(Delivery d, DeliveryStatus status, String changedBy, Role role, String note) {
        deliveryStatusHistoryRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(d.getId())
                .status(status)
                .changedBy(changedBy)
                .changedByRole(role)
                .note(note)
                .changedAt(LocalDateTime.now())
                .build());
    }

    private RouteStopStatus resolveStopStatus(RouteStop stop, Delivery d) {
        RouteStopStatus current = stop.getStatus();
        if (current == RouteStopStatus.PENDING || current == null) {
            RouteStopStatus fallback = mapDeliveryToRouteStopStatus(d.getStatus());
            return fallback != null ? fallback : current;
        }
        return current;
    }

}
