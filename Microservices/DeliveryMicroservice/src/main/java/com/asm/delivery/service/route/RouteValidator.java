package com.asm.delivery.service.route;

import com.asm.delivery.dto.request.CreateRouteRequest;
import com.asm.delivery.dto.request.UpdateRouteRequest;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopType;
import com.asm.delivery.entity.Vehicle;
import com.asm.delivery.entity.VehicleStatus;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.VehicleRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pre-condition checks for route planning: driver/vehicle availability, schedule and vehicle-slot
 * conflicts, vehicle payload capacity along the multi-depot load curve, and stop-window chronology.
 * Extracted from RoutePlanningService so the "is this route legal" rules sit apart from the
 * mutations that apply them. Each method throws an AppException on violation and returns silently
 * when the route is valid.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RouteValidator {

    private static final LocalTime DEFAULT_PLANNED_START = LocalTime.of(8, 0);
    private static final LocalTime DEFAULT_PLANNED_END = LocalTime.of(18, 0);

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final VehicleRepository vehicleRepository;
    private final DeliveryRepository deliveryRepository;
    private final TransportPort transportPort;

    /** Verify the route never exceeds the vehicle payload at any point along the load/unload curve. */
    public void assertRouteWeightWithinVehicleCapacity(Route route) {
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
                .filter(s -> !RoutePlanningService.isRemovedStatus(s.getStatus()) && s.getStopType() == RouteStopType.DELIVERY)
                .map(RouteStop::getDeliveryId)
                .toList();
        if (deliveryIds.isEmpty()) return;

        List<Delivery> deliveries = deliveryRepository.findAllByIdInWithOrder(deliveryIds);
        Map<UUID, BigDecimal> orderWeights = new HashMap<>();
        BigDecimal initialLoad = BigDecimal.ZERO;
        Map<UUID, BigDecimal> depotLoad = new HashMap<>();

        for (Delivery d : deliveries) {
            if (d.getOrder() != null && d.getOrder().getTotalWeightKg() != null) {
                BigDecimal weight = d.getOrder().getTotalWeightKg();
                orderWeights.put(d.getId(), weight);
                UUID depot = d.getSourceDepotId();
                if (depot == null || depot.equals(route.getDepotId())) {
                    initialLoad = initialLoad.add(weight);
                } else {
                    depotLoad.put(depot, depotLoad.getOrDefault(depot, BigDecimal.ZERO).add(weight));
                }
            }
        }

        BigDecimal running = initialLoad;
        BigDecimal peak = initialLoad;

        for (RouteStop stop : stops) {
            if (RoutePlanningService.isRemovedStatus(stop.getStatus())) continue;
            if (stop.getStopType() == RouteStopType.PICKUP && stop.getSourceDepotId() != null) {
                running = running.add(depotLoad.getOrDefault(stop.getSourceDepotId(), BigDecimal.ZERO));
                if (running.compareTo(peak) > 0) peak = running;
            } else if (stop.getStopType() == RouteStopType.DELIVERY && stop.getDeliveryId() != null) {
                running = running.subtract(orderWeights.getOrDefault(stop.getDeliveryId(), BigDecimal.ZERO));
            }
        }

        if (peak.compareTo(BigDecimal.valueOf(payloadKg)) > 0) {
            throw AppException.unprocessableEntity(String.format("Capacity Exceeded: Route peak load is %.2f kg, but vehicle '%s' is limited to %d kg.",
                    peak.doubleValue(), vehicle.getPlate(), payloadKg));
        }
    }

    /** Reject assignment of an inactive driver; tolerates transport lookup failures. */
    public void ensureDriverActive(UUID driverId) {
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

    /** Reject assignment of an inactive or non-available vehicle. */
    public void ensureVehicleAvailable(UUID vehicleId) {
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

    /** Reject a vehicle already committed to another validated/in-progress route on an overlapping window. */
    public void ensureNoVehicleConflict(UUID vehicleId, LocalDate date,
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

    /** Reject a driver already committed to another validated/in-progress route on an overlapping window. */
    public void ensureNoScheduleConflict(UUID driverId,
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

    /** Validate that each stop's window is well-formed and non-overlapping for a create request. */
    public void validateStopChronology(List<CreateRouteRequest.StopConfig> configs, LocalTime routeStart) {
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

    /** Validate stop windows for an update request (string-typed windows; overlap check disabled). */
    public void validateUpdateStopChronology(List<UpdateRouteRequest.StopConfig> configs, LocalTime routeStart) {
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

            if (!start.isBefore(end)) {
                throw AppException.badRequest("Stop #" + i + ": End time must be after start time");
            }
            lastEnd = end;
            i++;
        }
    }
}
