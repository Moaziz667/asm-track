package com.asm.delivery.service.route;

import com.asm.delivery.dto.request.CreateRouteRequest;
import com.asm.delivery.dto.request.UpdateRouteRequest;
import com.asm.delivery.dto.response.*;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.repository.*;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.erp.ErpSyncService;
import com.asm.delivery.service.AuditLogService;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.service.EventPublisher;
import com.asm.delivery.service.ProofOfDeliveryService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Filter;
import org.hibernate.Session;
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
import java.util.function.Supplier;
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
    private final EventPublisher eventPublisher;
    private final DeliveryStatusHistoryRepository deliveryStatusHistoryRepository;
    private final RouteWebSocketService routeWebSocketService;
    private final com.asm.delivery.sla.SlaStateService slaStateService;
    private final EntityManager entityManager;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private RoutePlanningService self;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSelf(@org.springframework.context.annotation.Lazy RoutePlanningService self) {
        this.self = self;
    }

    @Transactional
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
            // 1. Fetch route with stops in one transaction
            Route route = routeRepository.findFullRouteById(id)
                    .orElseThrow(() -> AppException.notFound("Route not found"));

            // 2. Fetch all deliveries and orders for these stops in one go
            List<UUID> deliveryIds = route.getStops().stream()
                    .map(RouteStop::getDeliveryId)
                    .toList();
            
            Map<UUID, Delivery> deliveryMap = deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                    .collect(Collectors.toMap(Delivery::getId, Function.identity(), (existing, replacement) -> existing));

            // 3. Fetch driver data outside (or if we are okay with connection being open)
            // Note: Since we need to return the response, we map everything here while session is potentially open
            // but we use the pre-fetched map to avoid LazyInit issues.
            
            DriverDTO driverData = null;
            if (route.getDriverId() != null) {
                try {
                    driverData = transportPort.getDriver(route.getDriverId().toString());
                } catch (Exception e) {
                    log.warn("Failed to fetch driver info for route {}: {}", id, e.getMessage());
                }
            }

            return toFullResponse(route, driverData, deliveryMap);
        } catch (Exception ex) {
            log.error("Failed to load full route {}", id, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Route getRouteTransactional(UUID id) {
        return getRoute(id);
    }

    @Transactional(readOnly = true)
    public RouteResponse getForDriver(UUID routeId, UUID driverId) {
        Route route = getRoute(routeId);
        ensureDriverOwnsRoute(route, driverId);
        return toResponse(route);
    }

    public RouteResponse create(CreateRouteRequest request, String createdBy) {
        if (request.getDepotId() == null) {
            throw AppException.badRequest("Depot is required — ETA and route optimization depend on it");
        }

        // 1. External reads (non-blocking / non-transactional)
        ensureDriverActive(request.getDriverId());

        // 2. Transactional creation
        return self.doCreate(request, createdBy);
    }

    @Transactional
    public RouteResponse doCreate(CreateRouteRequest request, String createdBy) {
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
        ensureVehicleAvailable(request.getVehicleId());
        ensureNoVehicleConflict(request.getVehicleId(), request.getDate(), plannedStartTime, plannedEndTime, null);        Route route = Route.builder()
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

    public RouteResponse update(UUID id, UpdateRouteRequest request) {
        // 1. Pre-update checks (external)
        if (request.getDriverId() != null) {
            ensureDriverActive(request.getDriverId());
        }

        // 2. Transactional update
        return self.doUpdate(id, request);
    }

    @Transactional
    public RouteResponse doUpdate(UUID id, UpdateRouteRequest request) {
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
        ensureVehicleAvailable(route.getVehicleId());
        ensureNoVehicleConflict(route.getVehicleId(), route.getDate(), effectiveStart, effectiveEnd, route.getId());

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

        reconcilePickupStops(route);
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

            /*
            if (start.isBefore(lastEnd)) {
                throw AppException.badRequest("Stop #" + i + ": Start time (" + start + ") is before previous stop ends (" + lastEnd + ")");
            }
            */
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
    public RouteResponse cancelStop(UUID routeId, UUID stopId, String reason) {
        Route route = getRoute(routeId);
        RouteStatus routeStatus = route.getStatus();
        if (routeStatus != RouteStatus.VALIDATED && routeStatus != RouteStatus.IN_PROGRESS) {
            throw AppException.badRequest("Stop cancellation only allowed on VALIDATED or IN_PROGRESS routes");
        }

        RouteStop stop = routeStopRepository.findById(stopId)
                .orElseThrow(() -> AppException.notFound("Stop not found: " + stopId));
        if (!stop.getRoute().getId().equals(routeId)) {
            throw AppException.badRequest("Stop does not belong to this route");
        }

        RouteStopStatus ss = stop.getStatus();
        if (ss == RouteStopStatus.IN_TRANSIT) {
            throw AppException.badRequest("Cannot cancel an IN_TRANSIT stop — driver must fail it from the app");
        }
        Set<RouteStopStatus> cancellable = Set.of(
                RouteStopStatus.PENDING, RouteStopStatus.SCHEDULED,
                RouteStopStatus.ARRIVED, RouteStopStatus.PICKED_UP);
        if (!cancellable.contains(ss)) {
            throw AppException.badRequest("Stop in status " + ss + " cannot be cancelled");
        }

        String cancelReason = (reason != null && !reason.isBlank()) ? reason.trim() : "CANCELLED";

        stop.setStatus(RouteStopStatus.REMOVED_CANCELLED);
        stop.setRemovedAt(LocalDateTime.now());
        stop.setRemovedReason(cancelReason);
        stop.setRemovedBy("ADMIN");
        routeStopRepository.save(stop);

        Delivery delivery = deliveryRepository.findByIdWithOrder(stop.getDeliveryId())
                .orElseThrow(() -> AppException.notFound("Delivery not found: " + stop.getDeliveryId()));

        // Cancelling a stop ends this shipment's journey — it does NOT re-pool as a fresh
        // UNSCHEDULED order (which made the SLA re-fire "as if newly imported").
        delivery.setStatus(DeliveryStatus.CANCELLED);
        delivery.setCancelledAt(LocalDateTime.now());
        delivery.setCancelReason(cancelReason);
        delivery.setCancelledBy(Role.ADMIN);
        deliveryRepository.save(delivery);
        appendHistory(delivery, DeliveryStatus.CANCELLED, "ADMIN", Role.ADMIN, "ROUTE_STOP_CANCELLED", Map.of("reason", cancelReason));
        slaStateService.refresh(delivery); // → terminal CANCELLED, no further alerts

        auditLogService.logAction(null, "CANCEL_STOP", "ROUTE_STOP", stopId.toString(),
                Map.of("routeId", routeId.toString(), "reason", cancelReason));

        // Notify driver via WebSocket + FCM
        if (route.getDriverId() != null) {
            String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : null;
            String erpOrderId = delivery.getOrder() != null ? delivery.getOrder().getErpOrderId() : null;
            routeWebSocketService.notifyDriverStopRemoved(route.getDriverId(), route.getId(), route.getName(), clientName, erpOrderId, cancelReason);
            eventPublisher.publishRouteStopRemoved(route, clientName, erpOrderId, cancelReason);
        }

        maybeAutoCloseRoute(route);
        return toResponse(route);
    }

    @Transactional
    public RouteResponse reassign(UUID routeId, UUID newDriverId) {
        Route route = getRoute(routeId);
        RouteStatus status = route.getStatus();
        if (status != RouteStatus.VALIDATED && status != RouteStatus.IN_PROGRESS) {
            throw AppException.badRequest("Only VALIDATED or IN_PROGRESS routes can be reassigned");
        }

        UUID oldDriverId = route.getDriverId();
        if (newDriverId.equals(oldDriverId)) {
            throw AppException.badRequest("New driver is the same as current driver");
        }

        route.setDriverId(newDriverId);
        route.setRouteVersion(route.getRouteVersion() != null ? route.getRouteVersion() + 1 : 2);
        routeRepository.save(route);

        // Update driverId on SCHEDULED deliveries only — PICKED_UP/IN_TRANSIT stays with original driver
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        List<UUID> deliveryIds = stops.stream()
                .filter(s -> !isRemovedStatus(s.getStatus()))
                .map(RouteStop::getDeliveryId)
                .toList();
        if (!deliveryIds.isEmpty()) {
            List<Delivery> deliveries = deliveryRepository.findAllById(deliveryIds);
            for (Delivery delivery : deliveries) {
                if (delivery.getStatus() == DeliveryStatus.SCHEDULED) {
                    delivery.setDriverId(newDriverId);
                    deliveryRepository.save(delivery);
                    appendHistory(delivery, DeliveryStatus.SCHEDULED, "ADMIN", Role.ADMIN,
                            "ROUTE_REASSIGNED", Map.of("driverId", newDriverId.toString()));
                }
            }
        }

        auditLogService.logAction(null, "REASSIGN_ROUTE", "ROUTE", routeId.toString(),
                Map.of("tournee", route.getName(), "oldDriverId", oldDriverId.toString(), "newDriverId", newDriverId.toString()));

        routeWebSocketService.notifyDriver(oldDriverId, "ROUTE_REASSIGNED_AWAY", route.getId(), route.getName());
        routeWebSocketService.notifyDriver(newDriverId, "ROUTE_ASSIGNED", route.getId(), route.getName());

        return toResponse(route);
    }

    @Transactional
    public RouteResponse addStop(UUID routeId, com.asm.delivery.dto.request.AddRouteStopRequest request) {
        Route route = getRoute(routeId);
        ensureDraft(route);
        int nextOrder = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId).size() + 1;
        addStopInternal(route, request.getDeliveryId(), nextOrder, request.getStartTimeWindow(), request.getEndTimeWindow(), request.getBufferMinutes());
        reconcilePickupStops(route);
        assertRouteWeightWithinVehicleCapacity(route);
        auditLogService.logAction(null, "ADD_STOP", "ROUTE", routeId.toString(),
                Map.of("tournee", route.getName(), "action", "Ajout d'un arret"));
        return toResponse(route);
    }

    @Transactional
    public RouteResponse addStopToValidated(UUID routeId, com.asm.delivery.dto.request.AddRouteStopRequest request) {
        Route route = getRoute(routeId);
        RouteStatus status = route.getStatus();
        if (status != RouteStatus.VALIDATED && status != RouteStatus.IN_PROGRESS) {
            throw AppException.badRequest("This endpoint only applies to VALIDATED or IN_PROGRESS routes");
        }
        // Count only non-removed stops for next order
        int nextOrder = (int) routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId)
                .stream().filter(s -> !isRemovedStatus(s.getStatus())).count() + 1;
        addStopInternal(route, request.getDeliveryId(), nextOrder, request.getStartTimeWindow(), request.getEndTimeWindow(), request.getBufferMinutes());
        reconcilePickupStops(route);
        // Also schedule the delivery
        deliveryRepository.findById(request.getDeliveryId()).ifPresent(delivery -> {
            if (delivery.getStatus() == DeliveryStatus.UNSCHEDULED) {
                delivery.setDriverId(route.getDriverId());
                delivery.setStatus(DeliveryStatus.SCHEDULED);
                delivery.setAssignedAt(LocalDateTime.now());
                deliveryRepository.save(delivery);
                appendHistory(delivery, DeliveryStatus.SCHEDULED, "ADMIN", Role.ADMIN, "ROUTE_STOP_ADDED", Map.of("routeName", route.getName() != null ? route.getName() : ""));
            }
        });
        route.setRouteVersion(route.getRouteVersion() != null ? route.getRouteVersion() + 1 : 2);
        routeRepository.save(route);
        auditLogService.logAction(null, "ADD_STOP_ACTIVE", "ROUTE", routeId.toString(),
                Map.of("tournee", route.getName(), "action", "Ajout d'un arret a une tournee active"));
        String addedClientName = deliveryRepository.findById(request.getDeliveryId())
                .map(d -> d.getOrder() != null ? d.getOrder().getClientName() : null)
                .orElse(null);
        routeWebSocketService.notifyDriverStopAdded(route.getDriverId(), route.getId(), route.getName(), addedClientName);
        eventPublisher.publishRouteStopAdded(route, addedClientName);
        return toResponse(route);
    }

    @Transactional
    public RouteResponse removeStop(UUID routeId, UUID stopId) {
        Route route = getRoute(routeId);
        RouteStatus routeStatus = route.getStatus();

        if (routeStatus == RouteStatus.CLOSED || routeStatus == RouteStatus.CANCELLED) {
            throw AppException.badRequest("Cannot modify a closed or cancelled route");
        }

        RouteStop stop = routeStopRepository.findByRouteIdAndId(routeId, stopId)
                .orElseThrow(() -> AppException.notFound("Route stop not found"));

        if (routeStatus == RouteStatus.VALIDATED || routeStatus == RouteStatus.IN_PROGRESS) {
            // Guard: cannot remove a stop that's already being actioned by the driver
            RouteStopStatus stopStatus = stop.getStatus();
            if (stopStatus == RouteStopStatus.ARRIVED
                    || stopStatus == RouteStopStatus.COMPLETED
                    || stopStatus == RouteStopStatus.FAILED
                    || stopStatus == RouteStopStatus.PICKED_UP
                    || stopStatus == RouteStopStatus.IN_TRANSIT
                    || stopStatus == RouteStopStatus.PARTIAL) {
                throw AppException.badRequest("Cannot remove stop that is already in progress, terminal or picked up");
            }
            // Soft-delete: mark as REMOVED_REPLANNED with audit fields
            stop.setStatus(RouteStopStatus.REMOVED_REPLANNED);
            stop.setRemovedAt(LocalDateTime.now());
            stop.setRemovedReason("REPLANNED");
            stop.setRemovedBy("ADMIN");
            routeStopRepository.save(stop);
        } else {
            // DRAFT: hard delete
            routeStopRepository.delete(stop);
        }

        // Reset delivery back to UNSCHEDULED
        deliveryRepository.findById(stop.getDeliveryId()).ifPresent(delivery -> {
            if (delivery.getStatus() == DeliveryStatus.SCHEDULED || 
                delivery.getStatus() == DeliveryStatus.FAILED ||
                delivery.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED) {
                delivery.setStatus(DeliveryStatus.UNSCHEDULED);
                delivery.setDriverId(null);
                delivery.setAssignedAt(null);
                deliveryRepository.save(delivery);
                appendHistory(delivery, DeliveryStatus.UNSCHEDULED, "ADMIN", Role.ADMIN, "ROUTE_STOP_REMOVED", Map.of("routeName", route.getName() != null ? route.getName() : ""));
                // Re-planning U-turn: recompute SLA but suppress the planning alarm briefly so it is
                // not re-flagged "as if newly imported" the instant it returns to the pool.
                slaStateService.refresh(delivery);
                slaStateService.applyReplanGrace(delivery.getId());
            }
        });

        auditLogService.logAction(null, "REMOVE_STOP", "ROUTE", routeId.toString(),
                Map.of("tournee", route.getName(), "action", "Suppression d'un arret"));

        // Increment route version to track plan mutation
        route.setRouteVersion(route.getRouteVersion() != null ? route.getRouteVersion() + 1 : 2);
        routeRepository.save(route);

        reconcilePickupStops(route);

        // Re-pack stop order on remaining active stops
        List<RouteStop> activeStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId)
                .stream().filter(s -> !isRemovedStatus(s.getStatus())).toList();
        for (int i = 0; i < activeStops.size(); i++) {
            activeStops.get(i).setStopOrder(i + 1);
        }
        if (!activeStops.isEmpty()) {
            routeStopRepository.saveAll(activeStops);
        }

        // Notify driver if route is active
        if (routeStatus == RouteStatus.VALIDATED || routeStatus == RouteStatus.IN_PROGRESS) {
            deliveryRepository.findById(stop.getDeliveryId()).ifPresent(d -> {
                String removedClientName = d.getOrder() != null ? d.getOrder().getClientName() : null;
                String removedErpId = d.getOrder() != null ? d.getOrder().getErpOrderId() : null;
                routeWebSocketService.notifyDriverStopRemoved(route.getDriverId(), route.getId(), route.getName(), removedClientName, removedErpId, null);
                eventPublisher.publishRouteStopRemoved(route, removedClientName, removedErpId, null);
            });
        }

        maybeAutoCloseRoute(route);
        return toResponse(route);
    }

    @Transactional
    public RouteResponse patchStop(UUID routeId, UUID stopId, com.asm.delivery.dto.request.PatchRouteStopRequest request) {
        Route route = getRoute(routeId);
        RouteStop stop = routeStopRepository.findByRouteIdAndId(routeId, stopId)
                .orElseThrow(() -> AppException.notFound("Route stop not found"));

        if (request.getStartTimeWindow() != null) stop.setStartTimeWindow(request.getStartTimeWindow());
        if (request.getEndTimeWindow()   != null) stop.setEndTimeWindow(request.getEndTimeWindow());
        if (request.getBufferMinutes()   != null) stop.setBufferMinutes(request.getBufferMinutes());

        if (stop.getStartTimeWindow() != null && stop.getEndTimeWindow() != null
                && !stop.getStartTimeWindow().isBefore(stop.getEndTimeWindow())) {
            throw AppException.badRequest("startTimeWindow must be before endTimeWindow");
        }

        routeStopRepository.save(stop);

        if (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS) {
            routeWebSocketService.notifyDriver(route.getDriverId(), "STOP_UPDATED", route.getId(), route.getName());
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

        Map<UUID, RouteStop> byId = existing.stream().collect(Collectors.toMap(RouteStop::getId, Function.identity(), (a, b) -> a));
        List<RouteStop> ordered = new ArrayList<>();
        for (int i = 0; i < stopIds.size(); i++) {
            RouteStop stop = byId.get(stopIds.get(i));
            if (stop == null) {
                throw AppException.badRequest("Stop id does not belong to this route");
            }
            stop.setStopOrder(i + 1);
            ordered.add(stop);
        }
        assertPickupPrecedence(ordered);
        routeStopRepository.saveAll(ordered);

        return toResponse(route);
    }

    /**
     * Toggle the `locked` flag on a route. Locked routes are excluded from
     * batch optimization runs ("Optimiser la sélection") so dispatchers can
     * freeze tournées they have already handed off to drivers.
     */
    @Transactional
    public RouteResponse setLocked(UUID routeId, boolean locked) {
        Route route = getRoute(routeId);
        route.setLocked(locked);
        routeRepository.save(route);
        return toResponse(route);
    }

    @Transactional
    public RouteResponse validate(UUID routeId) {
        Route route = getRoute(routeId);
        ensureDraft(route);

        reconcilePickupStops(route);
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        assertPickupPrecedence(stops);

        assertRouteWeightWithinVehicleCapacity(route);

        if (stops.isEmpty()) {
            throw AppException.badRequest("Cannot validate route without stops");
        }

        List<UUID> deliveryIds = stops.stream()
                .filter(s -> s.getStopType() == RouteStopType.DELIVERY && s.getDeliveryId() != null)
                .map(RouteStop::getDeliveryId).toList();
        Map<UUID, Delivery> deliveryMap = deliveryRepository.findAllByIdInWithOrder(deliveryIds).stream()
                .collect(Collectors.toMap(Delivery::getId, Function.identity(), (existing, replacement) -> existing));

        for (RouteStop stop : stops) {
            if (stop.getStopType() == RouteStopType.PICKUP) continue;

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
                appendHistory(delivery, DeliveryStatus.SCHEDULED, "SYSTEM", Role.SYSTEM, "ROUTE_VALIDATED_ASSIGNED", Map.of("driverId", route.getDriverId().toString(), "routeName", route.getName() != null ? route.getName() : ""));
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
                    : zoneRepository.findActiveZonesByPostalCodes( postalCodes.toArray(new String[0]));

            if (detectedZones.size() > 1) {
                String label = detectedZones.stream().map(Zone::getName).collect(Collectors.joining(" · "));
                validationWarnings.add("Route crosses multiple zones: " + label + " — confirm this is intentional");
            }
        }

        route.setStatus(RouteStatus.VALIDATED);
        route.setValidatedAt(LocalDateTime.now());
        route.setRouteVersion(route.getRouteVersion() != null ? route.getRouteVersion() + 1 : 2);
        RouteResponse response = toResponse(routeRepository.save(route));
        if (!validationWarnings.isEmpty()) {
            response.setValidationWarnings(validationWarnings);
        }

        routeWebSocketService.notifyDriver(route.getDriverId(), "ROUTE_ASSIGNED", route.getId(), route.getName());
        eventPublisher.publishRouteValidated(route);

        return response;
    }
    private void addStopInternal(Route route, UUID deliveryId, int stopOrder, LocalTime start, LocalTime end, Integer buffer) {
        routeStopRepository.findActiveByDeliveryId(deliveryId).ifPresent(existingStop -> {
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
        if (stops.isEmpty()) return;

        if (route.getVehicleId() == null) {
            throw AppException.badRequest("Route vehicle is required before this operation");
        }
        Vehicle vehicle = vehicleRepository.findById(route.getVehicleId()).orElse(null);
        if (vehicle == null) return;

        Integer payloadKg = vehicle.getPayloadKg();
        if (payloadKg == null || payloadKg <= 0) return;

        List<UUID> deliveryIds = stops.stream()
                .filter(s -> !isRemovedStatus(s.getStatus()) && s.getStopType() == RouteStopType.DELIVERY)
                .map(RouteStop::getDeliveryId)
                .toList();
        if (deliveryIds.isEmpty()) return;

        List<Delivery> deliveries = deliveryRepository.findAllByIdInWithOrder(deliveryIds);
        Map<UUID, java.math.BigDecimal> orderWeights = new HashMap<>();
        java.math.BigDecimal initialLoad = java.math.BigDecimal.ZERO;
        Map<UUID, java.math.BigDecimal> depotLoad = new HashMap<>();

        for (Delivery d : deliveries) {
            if (d.getOrder() != null && d.getOrder().getTotalWeightKg() != null) {
                java.math.BigDecimal weight = d.getOrder().getTotalWeightKg();
                orderWeights.put(d.getId(), weight);
                UUID depot = d.getSourceDepotId();
                if (depot == null || depot.equals(route.getDepotId())) {
                    initialLoad = initialLoad.add(weight);
                } else {
                    depotLoad.put(depot, depotLoad.getOrDefault(depot, java.math.BigDecimal.ZERO).add(weight));
                }
            }
        }

        java.math.BigDecimal running = initialLoad;
        java.math.BigDecimal peak = initialLoad;

        for (RouteStop stop : stops) {
            if (isRemovedStatus(stop.getStatus())) continue;
            if (stop.getStopType() == RouteStopType.PICKUP && stop.getSourceDepotId() != null) {
                running = running.add(depotLoad.getOrDefault(stop.getSourceDepotId(), java.math.BigDecimal.ZERO));
                if (running.compareTo(peak) > 0) peak = running;
            } else if (stop.getStopType() == RouteStopType.DELIVERY && stop.getDeliveryId() != null) {
                running = running.subtract(orderWeights.getOrDefault(stop.getDeliveryId(), java.math.BigDecimal.ZERO));
            }
        }

        if (peak.compareTo(java.math.BigDecimal.valueOf(payloadKg)) > 0) {
            throw AppException.unprocessableEntity(String.format("Capacity Exceeded: Route peak load is %.2f kg, but vehicle '%s' is limited to %d kg.",
                    peak.doubleValue(), vehicle.getPlate(), payloadKg));
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
                || status == RouteStopStatus.PARTIAL
                || status == RouteStopStatus.FAILED_ATTEMPT;
    }

    static boolean isRemovedStatus(RouteStopStatus status) {
        return status == RouteStopStatus.REMOVED_REPLANNED
                || status == RouteStopStatus.REMOVED_CANCELLED;
    }

    private void maybeAutoCloseRoute(Route route) {
        if (route.getStatus() != RouteStatus.VALIDATED && route.getStatus() != RouteStatus.IN_PROGRESS) {
            return;
        }

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        if (!stops.isEmpty()) {
            boolean allTerminal = stops.stream().allMatch(s -> isTerminalStopStatus(s.getStatus()) || isRemovedStatus(s.getStatus()));
            if (!allTerminal) return;
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

    private void ensureDriverActive(UUID driverId) {
        if (driverId == null) return;
        try {
            DriverDTO driver = transportPort.getDriver(driverId.toString());
            if (driver != null && Boolean.FALSE.equals(driver.getActive())) {
                throw AppException.conflict("Driver is inactive and cannot be assigned to a route");
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not verify driver active status for {}: {}", driverId, e.getMessage());
        }
    }

    private void ensureVehicleAvailable(UUID vehicleId) {
        if (vehicleId == null) return;
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> AppException.badRequest("Vehicle not found"));
        if (!Boolean.TRUE.equals(vehicle.getActive())) {
            throw AppException.conflict("Vehicle is inactive and cannot be assigned to a route");
        }
        if (vehicle.getVehicleStatus() != VehicleStatus.AVAILABLE) {
            throw AppException.conflict("Vehicle is not available (status: " + vehicle.getVehicleStatus().name() + ")");
        }
    }

    private void ensureNoVehicleConflict(UUID vehicleId, LocalDate date,
                                         LocalTime startTime, LocalTime endTime,
                                         UUID currentRouteId) {
        if (vehicleId == null) return;
        List<Route> sameDayRoutes = routeRepository.findAllByVehicleIdAndDate(vehicleId, date);
        for (Route existing : sameDayRoutes) {
            // DRAFT = tentative, pas encore validé → pas de lock de ressource
            if (existing.getStatus() == RouteStatus.DRAFT
                    || existing.getStatus() == RouteStatus.CLOSED
                    || existing.getStatus() == RouteStatus.CANCELLED) continue;
            if (currentRouteId != null && existing.getId().equals(currentRouteId)) continue;
            LocalTime existingStart = existing.getPlannedStartTime() != null ? existing.getPlannedStartTime() : DEFAULT_PLANNED_START;
            LocalTime existingEnd   = existing.getPlannedEndTime()   != null ? existing.getPlannedEndTime()   : DEFAULT_PLANNED_END;
            if (startTime.isBefore(existingEnd) && existingStart.isBefore(endTime)) {
                throw AppException.conflict("Ce véhicule est déjà affecté à une tournée validée sur ce créneau horaire");
            }
        }
    }

    private void ensureNoScheduleConflict(UUID driverId,
                                          LocalDate date,
                                          LocalTime startTime,
                                          LocalTime endTime,
                                          UUID currentRouteId) {
        List<Route> sameDayRoutes = routeRepository.findAllByDriverIdAndDate(driverId, date);
        for (Route existing : sameDayRoutes) {
            // DRAFT = brouillon, pas de lock — seules les routes VALIDATED/IN_PROGRESS bloquent
            if (existing.getStatus() == RouteStatus.DRAFT
                    || existing.getStatus() == RouteStatus.CLOSED
                    || existing.getStatus() == RouteStatus.CANCELLED) {
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
                .filter(s -> !isRemovedStatus(s.getStatus()))
                .toList();
        List<RouteStop> legacyStops = routeStops.stream()
                .filter(s -> isRemovedStatus(s.getStatus()))
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

        return RouteResponse.builder()
                .id(route.getId())

                .name(route.getName())
                .driverId(route.getDriverId())
                .vehicleId(route.getVehicleId())
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

    private RouteFullResponse toFullResponse(Route route, DriverDTO driverData, Map<UUID, Delivery> deliveryMap) {
        // --- Same initial logic as toResponse ---
        // Load stops from repository (guarantees stopOrder ASC, consistent with toResponse)
        List<RouteStop> allStops = route.getStops(); // Already fetched via findFullRouteById
        List<RouteStop> activeStops = allStops.stream()
                .filter(stop -> !isRemovedStatus(stop.getStatus()))
                .toList();

        Map<String, String> actorNames = new HashMap<>();
        // Note: Driver name resolution for history is omitted for brevity or handled by building a map if needed.
        // For PFE, we prioritize stability (fixing the LazyInit crash).

        List<RouteStopFullResponse> stops = activeStops.stream()
                .map(stop -> toFullStopResponse(stop, route, activeStops, actorNames, deliveryMap))
                .toList();

        List<RouteStopFullResponse> legacyStops = allStops.stream()
                .filter(stop -> isRemovedStatus(stop.getStatus()))
                .map(stop -> toFullStopResponse(stop, route, activeStops, actorNames, deliveryMap))
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

    private RouteStopFullResponse toFullStopResponse(RouteStop stop, Route route, List<RouteStop> activeStops, Map<String, String> actorNames, Map<UUID, Delivery> deliveryMap) {
        Delivery delivery = stop.getDeliveryId() != null ? deliveryMap.get(stop.getDeliveryId()) : null;
        if (delivery == null && stop.getDeliveryId() != null) {
            // Fallback for safety, though it shouldn't happen with the pre-fetch
            delivery = deliveryRepository.findByIdWithOrder(stop.getDeliveryId()).orElse(null);
        }

        com.asm.delivery.entity.Order orderInfo = delivery != null ? delivery.getOrder() : null;
        
        // Calculate delay details
        DelayCalculationService.DelayInfo delayInfo = delayCalculationService.calculateDelay(stop, route, activeStops);
        Integer delayMinutes = delayInfo != null ? delayInfo.delayMinutes : null;
        String delayStatus = delayInfo != null ? delayInfo.delayStatus : null;
        String delayReason = delayInfo != null ? delayInfo.delayReason : null;
        Integer transitSlaMinutesComputed = null; // Removed as it is not in DelayInfo


        UUID sourceDepotId = stop.getStopType() == RouteStopType.PICKUP ? stop.getSourceDepotId() : 
                             (delivery != null ? delivery.getSourceDepotId() : null);
        com.asm.delivery.entity.Depot sourceDepot = sourceDepotId != null ? depotRepository.findById(sourceDepotId).orElse(null) : null;

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
                .slaStatus(delayStatus != null ? SlaStatus.valueOf(delayStatus) : stop.getSlaStatus())
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

    private String resolveActorName(String changedBy, Role role) {
        if (changedBy == null) return null;
        if ("SYSTEM".equalsIgnoreCase(changedBy)) return "Système";
        try {
            UUID.fromString(changedBy);
            if (role == Role.DRIVER) {
                com.asm.delivery.transport.DriverDTO driver = transportPort.getDriver(changedBy);
                if (driver != null && driver.getName() != null) return driver.getName();
            }
            if (role == Role.DISPATCHER || role == Role.ADMIN) return "Dispatching";
            return changedBy.substring(0, 8).toUpperCase();
        } catch (IllegalArgumentException e) {
            return changedBy;
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

    private void appendHistory(Delivery d, DeliveryStatus status, String changedBy, Role role, String eventKey, Map<String, Object> params) {
        String jsonParams = "{}";
        try {
            jsonParams = objectMapper.writeValueAsString(params != null ? params : Map.of());
        } catch (Exception ignored) {}
        
        deliveryStatusHistoryRepository.save(DeliveryStatusHistory.builder()
                .deliveryId(d.getId())
                .status(status)
                .changedBy(changedBy)
                .changedByRole(role)
                .eventKey(eventKey)
                .eventParams(jsonParams)
                .changedAt(LocalDateTime.now())
                .build());
    }

    private RouteStopStatus resolveStopStatus(RouteStop stop, Delivery d) {
        RouteStopStatus current = stop.getStatus();
        if (current == RouteStopStatus.PENDING || current == null) {
            RouteStopStatus fallback = d != null ? mapDeliveryToRouteStopStatus(d.getStatus()) : null;
            return fallback != null ? fallback : (current != null ? current : RouteStopStatus.PENDING);
        }
        return current;
    }

    // ── Multi-depot Routing Core ──────────────────────────────────────────────────

    private void reconcilePickupStops(Route route) {
        if (route.getStatus() != RouteStatus.DRAFT && route.getStatus() != RouteStatus.VALIDATED) {
            return;
        }

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        List<RouteStop> deliveryStops = stops.stream()
                .filter(s -> !isRemovedStatus(s.getStatus()) && s.getStopType() == RouteStopType.DELIVERY)
                .toList();
        List<RouteStop> pickupStops = stops.stream()
                .filter(s -> !isRemovedStatus(s.getStatus()) && s.getStopType() == RouteStopType.PICKUP)
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

    private void normalizeStopOrder(Route route) {
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        List<RouteStop> activeStops = stops.stream().filter(s -> !isRemovedStatus(s.getStatus())).toList();
        
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

    private void assertPickupPrecedence(List<RouteStop> orderedStops) {
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
