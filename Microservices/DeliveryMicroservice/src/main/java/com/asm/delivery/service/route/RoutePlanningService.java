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
    private final com.asm.delivery.sla.SlaStateRepository slaStateRepository;
    private final PickupStopReconciler pickupStopReconciler;
    private final RouteAutoCloseService routeAutoCloseService;
    private final RouteValidator routeValidator;
    private final RouteResponseMapper routeResponseMapper;
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
        return routeRepository.findAllByOrderByDateDescCreatedAtDesc().stream().map(routeResponseMapper::toResponse).toList();
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
                .map(routeResponseMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RouteResponse get(UUID id) {
        return routeResponseMapper.toResponse(getRoute(id));
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

            return routeResponseMapper.toFullResponse(route, driverData, deliveryMap);
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
        return routeResponseMapper.toResponse(route);
    }

    public RouteResponse create(CreateRouteRequest request, String createdBy) {
        if (request.getDepotId() == null) {
            throw AppException.badRequest("Depot is required — ETA and route optimization depend on it");
        }

        // 1. External reads (non-blocking / non-transactional)
        routeValidator.ensureDriverActive(request.getDriverId());

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
        routeValidator.ensureNoScheduleConflict(request.getDriverId(), request.getDate(), plannedStartTime, plannedEndTime, null);
        routeValidator.ensureVehicleAvailable(request.getVehicleId());
        routeValidator.ensureNoVehicleConflict(request.getVehicleId(), request.getDate(), plannedStartTime, plannedEndTime, null);        Route route = Route.builder()
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
            routeValidator.validateStopChronology(request.getStopConfigs(), plannedStartTime);
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

        routeValidator.assertRouteWeightWithinVehicleCapacity(route);

        return routeResponseMapper.toResponse(routeRepository.findById(route.getId()).orElse(route));
    }

    public RouteResponse update(UUID id, UpdateRouteRequest request) {
        // 1. Pre-update checks (external)
        if (request.getDriverId() != null) {
            routeValidator.ensureDriverActive(request.getDriverId());
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
        routeValidator.ensureNoScheduleConflict(route.getDriverId(), route.getDate(), effectiveStart, effectiveEnd, route.getId());
        routeValidator.ensureVehicleAvailable(route.getVehicleId());
        routeValidator.ensureNoVehicleConflict(route.getVehicleId(), route.getDate(), effectiveStart, effectiveEnd, route.getId());

        if (route.getVehicleId() != null) {
            assignVehicleToDriver(route.getVehicleId(), route.getDriverId());
        }

        // --- UPDATE STOPS IF PROVIDED ---
        if (request.getStopConfigs() != null && !request.getStopConfigs().isEmpty()) {
            routeValidator.validateUpdateStopChronology(request.getStopConfigs(), effectiveStart);

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

        pickupStopReconciler.reconcile(route);
        routeValidator.assertRouteWeightWithinVehicleCapacity(route);

        auditLogService.logAction(null, "UPDATE_ROUTE", "ROUTE", route.getId().toString(),
                Map.of("tournee", route.getName(), "action", "Mise a jour de tournee"));

        return routeResponseMapper.toResponse(routeRepository.save(route));
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

        // Taking a stop off a validated route means "off this route, to be re-planned" — it does
        // NOT cancel the order (cancellation is a separate action in /deliveries). Reset to
        // UNSCHEDULED and apply the grace window so the SLA does not re-fire "as if newly imported".
        delivery.setStatus(DeliveryStatus.UNSCHEDULED);
        delivery.setDriverId(null);
        delivery.setAssignedAt(null);
        delivery.setPickedUpAt(null);
        deliveryRepository.save(delivery);
        appendHistory(delivery, DeliveryStatus.UNSCHEDULED, "ADMIN", Role.ADMIN, "ROUTE_STOP_CANCELLED", Map.of("reason", cancelReason));
        slaStateService.refresh(delivery);
        slaStateService.applyReplanGrace(delivery.getId());

        auditLogService.logAction(null, "CANCEL_STOP", "ROUTE_STOP", stopId.toString(),
                Map.of("routeId", routeId.toString(), "reason", cancelReason));

        // Notify driver via WebSocket + FCM
        if (route.getDriverId() != null) {
            String clientName = delivery.getOrder() != null ? delivery.getOrder().getClientName() : null;
            String erpOrderId = delivery.getOrder() != null ? delivery.getOrder().getErpOrderId() : null;
            routeWebSocketService.notifyDriverStopRemoved(route.getDriverId(), route.getId(), route.getName(), clientName, erpOrderId, cancelReason);
            eventPublisher.publishRouteStopRemoved(route, clientName, erpOrderId, cancelReason);
        }

        routeAutoCloseService.finalizeIfResolved(route);
        return routeResponseMapper.toResponse(route);
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

        return routeResponseMapper.toResponse(route);
    }

    @Transactional
    public RouteResponse addStop(UUID routeId, com.asm.delivery.dto.request.AddRouteStopRequest request) {
        Route route = getRoute(routeId);
        ensureDraft(route);
        int nextOrder = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId).size() + 1;
        addStopInternal(route, request.getDeliveryId(), nextOrder, request.getStartTimeWindow(), request.getEndTimeWindow(), request.getBufferMinutes());
        pickupStopReconciler.reconcile(route);
        routeValidator.assertRouteWeightWithinVehicleCapacity(route);
        auditLogService.logAction(null, "ADD_STOP", "ROUTE", routeId.toString(),
                Map.of("tournee", route.getName(), "action", "Ajout d'un arret"));
        return routeResponseMapper.toResponse(route);
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
        pickupStopReconciler.reconcile(route);
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
        return routeResponseMapper.toResponse(route);
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

        pickupStopReconciler.reconcile(route);

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

        routeAutoCloseService.finalizeIfResolved(route);
        return routeResponseMapper.toResponse(route);
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

        return routeResponseMapper.toResponse(route);
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
        pickupStopReconciler.assertPickupPrecedence(ordered);
        routeStopRepository.saveAll(ordered);

        return routeResponseMapper.toResponse(route);
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
        return routeResponseMapper.toResponse(route);
    }

    @Transactional
    public RouteResponse validate(UUID routeId) {
        Route route = getRoute(routeId);
        ensureDraft(route);

        pickupStopReconciler.reconcile(route);
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
        pickupStopReconciler.assertPickupPrecedence(stops);

        routeValidator.assertRouteWeightWithinVehicleCapacity(route);

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
        RouteResponse response = routeResponseMapper.toResponse(routeRepository.save(route));
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


    static boolean isRemovedStatus(RouteStopStatus status) {
        return status == RouteStopStatus.REMOVED_REPLANNED
                || status == RouteStopStatus.REMOVED_CANCELLED;
    }

    private static void validateScheduleWindow(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null) {
            throw AppException.badRequest("Route schedule window is required");
        }
        if (!startTime.isBefore(endTime)) {
            throw AppException.badRequest("Route start time must be before end time");
        }
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





    // ─── SLA Summary ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SlaSummaryResponse getSlaSummary() {
        // Reads the unified SlaState source of truth (replaces the legacy RouteStop.slaStatus path).
        // "late" = live breach or terminal-late; "onTime" = on-track / at-risk / met.
        long late = slaStateRepository.countByHealthIn(java.util.List.of(
                com.asm.delivery.sla.SlaHealth.BREACHED, com.asm.delivery.sla.SlaHealth.LATE));
        long onTime = slaStateRepository.countByHealthIn(java.util.List.of(
                com.asm.delivery.sla.SlaHealth.ON_TRACK, com.asm.delivery.sla.SlaHealth.AT_RISK, com.asm.delivery.sla.SlaHealth.MET));

        List<SlaSummaryResponse.SlaStopItem> lateStops = slaStateRepository.findByHealthIn(java.util.List.of(
                com.asm.delivery.sla.SlaHealth.BREACHED, com.asm.delivery.sla.SlaHealth.LATE)).stream()
            .sorted((a, b) -> {
                if (a.getDueAt() == null && b.getDueAt() == null) return 0;
                if (a.getDueAt() == null) return 1;
                if (b.getDueAt() == null) return -1;
                return a.getDueAt().compareTo(b.getDueAt());
            })
            .limit(10)
            .map(st -> SlaSummaryResponse.SlaStopItem.builder()
                .deliveryId(st.getDeliveryId())
                .etaAt(st.getDueAt())
                .slaDeadline(st.getDueAt())
                .slaStatus(st.getHealth() != null ? st.getHealth().name() : null)
                .build())
            .collect(Collectors.toList());

        return SlaSummaryResponse.builder()
            .onTime((int) onTime)
            .late((int) late)
            .total((int) (onTime + late))
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



}
