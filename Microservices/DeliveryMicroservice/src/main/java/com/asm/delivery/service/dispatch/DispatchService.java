package com.asm.delivery.service.dispatch;

import com.asm.delivery.entity.Order;
import java.time.LocalDateTime;

import com.asm.delivery.dto.request.AssignDeliveryRequest;
import com.asm.delivery.dto.request.PinDropoffRequest;
import com.asm.delivery.dto.response.AdminDeliveryDetailResponse;
import com.asm.delivery.dto.response.AdminDeliverySummaryResponse;
import com.asm.delivery.dto.response.AdminDriverResponse;
import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.repository.*;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.service.DriverDeliveryService;
import com.asm.delivery.service.GeocodingService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DispatchService {

    private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
            DeliveryStatus.SCHEDULED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
    );

    private final DeliveryRepository deliveryRepo;
    private final TransportPort transportPort;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final OrderRepository orderRepo;
    private final ProofOfDeliveryRepository podRepo;
    private final DriverDeliveryService driverDeliveryService;
    private final EntityManager entityManager;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final ZoneRepository zoneRepository;
    private final GeocodingService geocodingService;
    private final VehicleRepository vehicleRepository;
    private final com.asm.delivery.service.route.RouteWebSocketService routeWebSocketService;
    private final com.asm.delivery.service.AuditLogService auditLogService;

    // ── Search deliveries ─────────────────────────────────────────────────────

    public Page<AdminDeliverySummaryResponse> searchDeliveries(
            DeliveryStatus status,
            UUID driverId,
            LocalDate date,
            OrderSource source,
            UUID zoneId,
            Boolean unpinned,
            Pageable pageable
    ) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Delivery> cq = cb.createQuery(Delivery.class);
        Root<Delivery> root = cq.from(Delivery.class);
        root.fetch("order", JoinType.INNER);
        List<Predicate> predicates = buildPredicates(cb, root, status, driverId, date, source, zoneId, unpinned);
        cq.select(root).distinct(true).where(predicates.toArray(Predicate[]::new))
                .orderBy(cb.desc(root.get("createdAt")));

        TypedQuery<Delivery> query = entityManager.createQuery(cq);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        List<Delivery> deliveries = query.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Delivery> countRoot = countQuery.from(Delivery.class);
        List<Predicate> countPredicates = buildPredicates(cb, countRoot, status, driverId, date, source, zoneId, unpinned);
        countQuery.select(cb.count(countRoot)).where(countPredicates.toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        // Bulk-fetch driver info from Driver Service
        Map<String, DriverDTO> driverMap = loadDriverMap(deliveries);
                Map<UUID, RouteInfo> routeInfoByDeliveryId = loadRouteInfoMap(deliveries);

        List<AdminDeliverySummaryResponse> content = deliveries.stream()
                .map(d -> {
                    DriverDTO driver = d.getDriverId() != null ? driverMap.get(d.getDriverId().toString()) : null;
                                        return toSummaryResponse(d, driver, routeInfoByDeliveryId.get(d.getId()));
                })
                .toList();

        return new PageImpl<>(content, pageable, total);
    }

    // ── Delivery detail ───────────────────────────────────────────────────────

    public AdminDeliveryDetailResponse getDeliveryDetail(UUID id) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(id)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        DriverDTO driver = null;
        if (delivery.getDriverId() != null) {
            driver = transportPort.getDriver(delivery.getDriverId().toString());
        }

        List<StatusHistoryResponse> history = historyRepo
                .findByDeliveryIdOrderByChangedAtAsc(delivery.getId()).stream()
                .map(this::toHistoryResponse)
                .toList();

        boolean podExists = podRepo.existsByDeliveryId(delivery.getId());

        return toDetailResponse(delivery, driver, history, podExists);
    }

    // ── Drivers list ──────────────────────────────────────────────────────────

    public List<AdminDriverResponse> getDrivers() {
                List<DriverDTO> drivers = new ArrayList<>(transportPort.getAvailableDrivers());

        // Map driverId → active deliveryId for currently active deliveries
        Map<String, UUID> activeDeliveryMap = deliveryRepo.findActiveDeliveries(ACTIVE_STATUSES).stream()
                .filter(d -> d.getDriverId() != null)
                .collect(Collectors.toMap(
                        d -> d.getDriverId().toString(),
                        Delivery::getId,
                        (existing, replacement) -> existing
                ));

                // Ensure busy drivers appear in the list even if the transport endpoint only returns available ones.
                java.util.Set<String> knownDriverIds = drivers.stream().map(DriverDTO::getId).collect(Collectors.toSet());
                for (String driverId : activeDeliveryMap.keySet()) {
                        if (!knownDriverIds.contains(driverId)) {
                                DriverDTO busyDriver = transportPort.getDriver(driverId);
                                if (busyDriver != null) {
                                        drivers.add(busyDriver);
                                        knownDriverIds.add(driverId);
                                }
                        }
                }

                LocalDate today = LocalDate.now();
                Map<String, UUID> activeRouteMap = routeRepository.findAll().stream()
                        .filter(route -> today.equals(route.getDate()))
                        .filter(route -> route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)
                        .collect(Collectors.toMap(
                                route -> route.getDriverId().toString(),
                                Route::getId,
                                (existing, replacement) -> existing
                        ));
                for (String driverId : activeRouteMap.keySet()) {
                        if (!knownDriverIds.contains(driverId)) {
                                DriverDTO busyDriver = transportPort.getDriver(driverId);
                                if (busyDriver != null) {
                                        drivers.add(busyDriver);
                                        knownDriverIds.add(driverId);
                                }
                        }
                }

        return drivers.stream()
                .map(d -> AdminDriverResponse.builder()
                        .id(parseUuid(d.getId()))
                        .name(d.getName())
                        .phone(d.getPhone())
                        .currentLat(d.getCurrentLat() != null ? BigDecimal.valueOf(d.getCurrentLat()) : null)
                        .currentLng(d.getCurrentLng() != null ? BigDecimal.valueOf(d.getCurrentLng()) : null)
                        .activeDeliveryId(activeDeliveryMap.get(d.getId()))
                        .activeRouteId(activeRouteMap.get(d.getId()))
                        .createdAt(d.getCreatedAt() != null ? parseDateTime(d.getCreatedAt()) : null)
                        .build())
                .toList();
    }
    // ── Assign ────────────────────────────────────────────────────────────────

    @Transactional
    public AdminDeliveryDetailResponse assignDelivery(UUID deliveryId, AssignDeliveryRequest request, UserPrincipal principal) {
        driverDeliveryService.accept(deliveryId, request.getDriverId(), principal);
        return getDeliveryDetail(deliveryId);
    }

    @Transactional
    public AdminDeliveryDetailResponse pinDropoff(UUID deliveryId, PinDropoffRequest request) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        // Lock check: cannot re-pin once the order is picked up or beyond
        if (delivery.getStatus() == DeliveryStatus.PICKED_UP
                || delivery.getStatus() == DeliveryStatus.IN_TRANSIT
                || delivery.getStatus() == DeliveryStatus.DELIVERED
                || delivery.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED
                || delivery.getStatus() == DeliveryStatus.FAILED) {
            throw AppException.badRequest("Pin locked: order is in transit or completed. Close the route and create a new one if the address is wrong.");
        }

        Order order = delivery.getOrder();
        if (order == null) {
            throw AppException.badRequest("Delivery has no order attached");
        }

        order.setDropoffLat(request.getLat());
        order.setDropoffLng(request.getLng());

                String requestedAddress = normalizeText(request.getDropoffAddress());
                String requestedCity = normalizeText(request.getDropoffCity());
                String requestedPostalCode = normalizePostalCode(request.getDropoffPostalCode());
                String requestedCountryCode = normalizeText(request.getDropoffCountryCode());

        if (StringUtils.hasText(request.getDropoffAddress())) {
                        order.setDropoffAddress(requestedAddress);
        }
        if (StringUtils.hasText(request.getDropoffCity())) {
                        order.setDropoffCity(requestedCity);
        }
        if (StringUtils.hasText(request.getDropoffPostalCode())) {
                        order.setDropoffPostalCode(requestedPostalCode);
        }
        if (StringUtils.hasText(request.getDropoffCountryCode())) {
                        order.setDropoffCountryCode(requestedCountryCode);
                }

                // Robust fallback: enrich missing fields from pin coordinates.
                GeocodeSuggestionResponse reverse = geocodingService.reverseGeocode(
                                request.getLat().doubleValue(),
                                request.getLng().doubleValue()
                );
                if (!StringUtils.hasText(requestedAddress) && reverse != null && StringUtils.hasText(reverse.getDisplayName())) {
                        order.setDropoffAddress(reverse.getDisplayName().trim());
                }
                if (!StringUtils.hasText(requestedCity) && reverse != null && StringUtils.hasText(reverse.getCity())) {
                        order.setDropoffCity(reverse.getCity().trim());
                }
                if (!StringUtils.hasText(requestedPostalCode) && reverse != null && StringUtils.hasText(reverse.getPostalCode())) {
                        order.setDropoffPostalCode(normalizePostalCode(reverse.getPostalCode()));
        }

        // Zone detection: postal code first (precise), fall back to city name
                String postalCode = normalizePostalCode(order.getDropoffPostalCode());
                if (StringUtils.hasText(postalCode)) {
                        order.setDropoffPostalCode(postalCode);
                }
                String city = normalizeText(order.getDropoffCity());
                if (StringUtils.hasText(city)) {
                        order.setDropoffCity(city);
                }
        boolean zoneFound = false;
        if (StringUtils.hasText(postalCode)) {
                        var zoneByPostal = zoneRepository.findActiveByPostalCodeMember(postalCode);
            if (zoneByPostal.isPresent()) {
                order.setZoneId(zoneByPostal.get().getId());
                zoneFound = true;
            }
        }
        if (!zoneFound && StringUtils.hasText(city)) {
            zoneRepository.findActiveByCityMember(city.trim()).ifPresentOrElse(
                    zone -> order.setZoneId(zone.getId()),
                    () -> order.setZoneId(null)
            );
        } else if (!zoneFound) {
            order.setZoneId(null);
        }

        orderRepo.save(order);

        // Mark associated route as needing recalculation if already in a route
        routeStopRepository.findActiveByDeliveryId(deliveryId).ifPresent(stop -> {
            Route route = stop.getRoute();
            if (route != null && (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)) {
                route.setIsOptimized(false);
                routeRepository.save(route);
            }
        });

        return getDeliveryDetail(deliveryId);
    }
    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<Predicate> buildPredicates(CriteriaBuilder cb,
                                            Root<Delivery> root,
                                            DeliveryStatus status,
                                            UUID driverId,
                                            LocalDate date,
                                            OrderSource source,
                                            UUID zoneId,
                                            Boolean unpinned) {
        List<Predicate> predicates = new ArrayList<>();
        if (status != null) {
            predicates.add(cb.equal(root.get("status"), status));
        }
        if (driverId != null) {
            predicates.add(cb.equal(root.get("driverId"), driverId));
        }
        if (date != null) {
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = start.plusDays(1);
            predicates.add(cb.between(root.get("createdAt"), start, end));
        }
        // source, zoneId, unpinned all require a join on order
        if (source != null || zoneId != null || Boolean.TRUE.equals(unpinned)) {
            Join<Delivery, Order> orderJoin = root.join("order", JoinType.INNER);
            if (source != null) {
                predicates.add(cb.equal(orderJoin.get("source"), source));
            }
            if (zoneId != null) {
                predicates.add(cb.equal(orderJoin.get("zoneId"), zoneId));
            }
            if (Boolean.TRUE.equals(unpinned)) {
                predicates.add(cb.isNull(orderJoin.get("dropoffLat")));
            }
        }
        return predicates;
    }

    /** Bulk-fetch all unique drivers needed for a list of deliveries. */
    private Map<String, DriverDTO> loadDriverMap(List<Delivery> deliveries) {
        Map<String, DriverDTO> map = new HashMap<>();
        deliveries.stream()
                .map(Delivery::getDriverId)
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .distinct()
                .forEach(id -> {
                    DriverDTO dto = transportPort.getDriver(id);
                    if (dto != null) map.put(id, dto);
                });
        return map;
    }

    private Map<UUID, RouteInfo> loadRouteInfoMap(List<Delivery> deliveries) {
        List<UUID> deliveryIds = deliveries.stream()
                .map(Delivery::getId)
                .filter(Objects::nonNull)
                .toList();

        if (deliveryIds.isEmpty()) {
            return Map.of();
        }

        return routeStopRepository.findAllByDeliveryIdInWithRoute(deliveryIds).stream()
                .filter(routeStop -> routeStop.getRoute() != null)
                .collect(Collectors.toMap(
                        com.asm.delivery.entity.RouteStop::getDeliveryId,
                        routeStop -> new RouteInfo(routeStop.getRoute().getId(), routeStop.getRoute().getName()),
                        (existing, replacement) -> existing
                ));
    }

    private AdminDeliverySummaryResponse toSummaryResponse(Delivery d, DriverDTO driver, RouteInfo routeInfo) {
        Order order = d.getOrder();
        boolean isDropoffPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;
        Zone zone = (order != null && order.getZoneId() != null)
                ? zoneRepository.findById(order.getZoneId()).orElse(null)
                : null;
        return AdminDeliverySummaryResponse.builder()
                .deliveryId(d.getId())
                .orderId(order != null ? order.getId() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                .status(d.getStatus().name())
                .source(order != null ? order.getSource() : null)
                .clientName(order != null ? order.getClientName() : null)
                .dropoffAddress(order != null ? order.getDropoffAddress() : null)
                .dropoffCity(order != null ? order.getDropoffCity() : null)
                .dropoffPostalCode(order != null ? order.getDropoffPostalCode() : null)
                .dropoffLat(order != null ? order.getDropoffLat() : null)
                .dropoffLng(order != null ? order.getDropoffLng() : null)
                .dropoffPinned(isDropoffPinned)
                .zoneId(zone != null ? zone.getId() : null)
                .zoneName(zone != null ? zone.getName() : null)
                .zoneColor(zone != null ? zone.getColor() : null)
                .driverId(d.getDriverId())
                .driverName(driver != null ? driver.getName() : null)
                .driverPhone(driver != null ? driver.getPhone() : null)
                .totalAmount(order != null ? order.getTotalAmount() : null)
                .totalWeightKg(order != null ? order.getTotalWeightKg() : null)
                .routeDistanceKm(d.getRouteDistanceKm())
                .routeDurationMinutes(d.getRouteDurationMinutes())
                .transitSlaMinutesComputed(d.getTransitSlaMinutesComputed())
                .routeEtaAt(d.getRouteEtaAt())
                .routeGeometry(d.getRouteGeometry())
                .routeProvider(d.getRouteProvider())
                .createdAt(d.getCreatedAt())
                .assignedAt(d.getAssignedAt())
                .inTransitAt(d.getInTransitAt())
                .completedAt(d.getCompletedAt())
                .failedAt(d.getFailedAt())
                .cancelledAt(d.getCancelledAt())
                .updatedAt(d.getUpdatedAt())
                .totalQuantity(order != null ? (order.getTotalQuantity() != null && order.getTotalQuantity() > 0 
                    ? order.getTotalQuantity() 
                    : (order.getItems() != null ? order.getItems().stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum() : 0)) : null)
                .itemsSummary(order != null && order.getItems() != null 
                    ? order.getItems().stream()
                        .map(i -> i.getQuantity() + "x " + (i.getName() != null ? i.getName() : "Item"))
                        .collect(Collectors.joining(", "))
                    : null)
                .items(order != null && order.getItems() != null ? new ArrayList<>(order.getItems()) : null)
                .build();
    }

        private record RouteInfo(UUID routeId, String routeName) {}

        private String normalizeText(String value) {
                if (!StringUtils.hasText(value)) {
                        return null;
                }
                return value.trim();
        }

        private String normalizePostalCode(String value) {
                if (!StringUtils.hasText(value)) {
                        return null;
                }
                return value.trim().replaceAll("\\s+", "");
        }
    private AdminDeliveryDetailResponse toDetailResponse(Delivery d,
                                                         DriverDTO driver,
                                                         List<StatusHistoryResponse> history,
                                                         boolean podExists) {
        Order order = d.getOrder();
        boolean isDropoffPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;
        Zone zone = (order != null && order.getZoneId() != null)
                ? zoneRepository.findById(order.getZoneId()).orElse(null)
                : null;
        return AdminDeliveryDetailResponse.builder()
                .deliveryId(d.getId())
                .orderId(order != null ? order.getId() : null)
                .status(d.getStatus().name())
                .failureCode(d.getFailureCode() != null ? d.getFailureCode().name() : null)
                .failureComment(d.getFailReason())
                .driverId(d.getDriverId())
                .driverName(driver != null ? driver.getName() : null)
                .driverPhone(driver != null ? driver.getPhone() : null)
                .source(order != null ? order.getSource() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .clientName(order != null ? order.getClientName() : null)
                .clientPhone(order != null ? order.getClientPhone() : null)
                .clientEmail(order != null ? order.getClientEmail() : null)
                .dropoffAddress(order != null ? order.getDropoffAddress() : null)
                .dropoffCity(order != null ? order.getDropoffCity() : null)
                .dropoffPostalCode(order != null ? order.getDropoffPostalCode() : null)
                .dropoffCountryCode(order != null ? order.getDropoffCountryCode() : null)
                .dropoffLat(order != null ? order.getDropoffLat() : null)
                .dropoffLng(order != null ? order.getDropoffLng() : null)
                .dropoffPinned(isDropoffPinned)
                .zoneId(zone != null ? zone.getId() : null)
                .zoneName(zone != null ? zone.getName() : null)
                .zoneColor(zone != null ? zone.getColor() : null)
                .deliveryInstructions(order != null ? order.getDeliveryInstructions() : null)
                .items(order != null && order.getItems() != null ? new ArrayList<>(order.getItems()) : null)
                .totalAmount(order != null ? order.getTotalAmount() : null)
                .totalWeightKg(order != null ? order.getTotalWeightKg() : null)
                .routeDistanceKm(d.getRouteDistanceKm())
                .routeDurationMinutes(d.getRouteDurationMinutes())
                .transitSlaMinutesComputed(d.getTransitSlaMinutesComputed())
                .routeEtaAt(d.getRouteEtaAt())
                .routeGeometry(d.getRouteGeometry())
                .routeProvider(d.getRouteProvider())
                .currency(order != null ? order.getCurrency() : null)
                .odooSyncStatus(order != null ? order.getOdooSyncStatus() : null)
                .odooBackorderId(order != null ? order.getOdooBackorderId() : null)
                .createdAt(d.getCreatedAt())
                .assignedAt(d.getAssignedAt())
                .pickedUpAt(d.getPickedUpAt())
                .inTransitAt(d.getInTransitAt())
                .completedAt(d.getCompletedAt())
                .failedAt(d.getFailedAt())
                .cancelledAt(d.getCancelledAt())
                .podExists(podExists)
                .statusHistory(history)
                .build();
    }

    private StatusHistoryResponse toHistoryResponse(DeliveryStatusHistory h) {
        String actorDisplay = resolveActorName(h.getChangedBy(), h.getChangedByRole());
        return StatusHistoryResponse.builder()
                .id(h.getId() != null ? h.getId().toString() : null)
                .status(h.getStatus().name())
                .actor(actorDisplay)
                .timestamp(h.getChangedAt())
                .changedBy(actorDisplay)
                .changedByRole(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                .note(h.getNote())
                .changedAt(h.getChangedAt())
                .build();
    }

    private String resolveActorName(String changedBy, Role role) {
        if (changedBy == null) return null;
        if ("SYSTEM".equalsIgnoreCase(changedBy)) return "Système";
        try {
            UUID.fromString(changedBy);
            if (role == Role.DRIVER) {
                DriverDTO driver = transportPort.getDriver(changedBy);
                if (driver != null && driver.getName() != null) return driver.getName();
            }
            if (role == Role.DISPATCHER || role == Role.ADMIN) return "Dispatching";
            return changedBy.substring(0, 8).toUpperCase();
        } catch (IllegalArgumentException e) {
            return changedBy;
        }
    }

    private static UUID parseUuid(String id) {
        if (id == null) return null;
        try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
    }

    private static LocalDateTime parseDateTime(String dt) {
        if (dt == null) return null;
        try { return LocalDateTime.parse(dt); } catch (Exception e) { return null; }
    }

    @Transactional
    public com.asm.delivery.dto.response.TransferStopsResponse transferStops(com.asm.delivery.dto.request.TransferStopsRequest req) {
        Route sourceRoute = routeRepository.findById(req.getSourceRouteId())
                .orElseThrow(() -> AppException.notFound("Source route not found"));

        if (sourceRoute.getStatus() == RouteStatus.CLOSED || sourceRoute.getStatus() == RouteStatus.CANCELLED) {
            throw AppException.badRequest("Cannot transfer from a closed or cancelled route");
        }

        Route targetRoute;
        if (req.getTargetRouteId() != null) {
            targetRoute = routeRepository.findById(req.getTargetRouteId())
                    .orElseThrow(() -> AppException.notFound("Target route not found"));
            if (targetRoute.getStatus() == RouteStatus.CLOSED || targetRoute.getStatus() == RouteStatus.CANCELLED) {
                throw AppException.badRequest("Cannot transfer to a closed or cancelled route");
            }
        } else {
            if (req.getTargetDriverId() == null) {
                throw AppException.badRequest("targetDriverId is required when creating a new route");
            }
            targetRoute = Route.builder()
                    .name("Transfer from " + sourceRoute.getName())
                    .driverId(req.getTargetDriverId())
                    .vehicleId(req.getTargetVehicleId())
                    .date(LocalDate.now())
                    .status(RouteStatus.DRAFT)
                    .createdBy("ADMIN")
                    .build();
            targetRoute = routeRepository.save(targetRoute);
            auditLogService.logAction(null, "CREATE_ROUTE", "ROUTE", targetRoute.getId().toString(),
                    Map.of("tournee", targetRoute.getName(), "reason", "Auto-created for transfer"));
        }

        List<RouteStop> sourceStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(sourceRoute.getId());
        Map<UUID, RouteStop> sourceStopMap = sourceStops.stream()
                .collect(Collectors.toMap(RouteStop::getId, s -> s));

        List<RouteStop> targetStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(targetRoute.getId());
        int startOrder = req.getInsertAtOrder() != null ? req.getInsertAtOrder() : targetStops.size() + 1;

        List<com.asm.delivery.dto.response.TransferStopsResponse.Warning> warnings = new ArrayList<>();
        List<UUID> transferredStops = new ArrayList<>();

        int currentOrder = startOrder;
        for (UUID stopId : req.getStopIds()) {
            RouteStop stop = sourceStopMap.get(stopId);
            if (stop == null) {
                throw AppException.badRequest("Stop " + stopId + " not found in source route");
            }

            RouteStopStatus status = stop.getStatus();
            if (status == RouteStopStatus.COMPLETED || status == RouteStopStatus.FAILED || status == RouteStopStatus.PARTIAL) {
                throw AppException.badRequest("Cannot transfer a completed or failed stop");
            }

            if (status == RouteStopStatus.PICKED_UP || status == RouteStopStatus.IN_TRANSIT) {
                if (!StringUtils.hasText(req.getReason())) {
                    throw AppException.badRequest("Reason is required when transferring picked up packages");
                }
                // Flag for formal handoff — Driver B must confirm physical receipt
                stop.setRequiresHandoff(true);
                stop.setHandoffFromDriverId(sourceRoute.getDriverId());
                stop.setHandoffToDriverId(targetRoute.getDriverId());
                stop.setHandoffConfirmedAt(null);

                warnings.add(com.asm.delivery.dto.response.TransferStopsResponse.Warning.builder()
                        .stopId(stopId)
                        .code("HANDOFF")
                        .detail("Stop is already picked up. Hand-off required.")
                        .build());
            }

            stop.setRoute(targetRoute);
            stop.setStopOrder(currentOrder++);
            stop.setStatus(RouteStopStatus.PENDING);
            routeStopRepository.save(stop);

            Delivery delivery = deliveryRepo.findById(stop.getDeliveryId()).orElse(null);
            if (delivery != null) {
                delivery.setDriverId(targetRoute.getDriverId());
                if (targetRoute.getStatus() == RouteStatus.VALIDATED || targetRoute.getStatus() == RouteStatus.IN_PROGRESS) {
                    delivery.setStatus(DeliveryStatus.SCHEDULED);
                } else {
                    delivery.setStatus(DeliveryStatus.UNSCHEDULED); // pending validation
                }
                deliveryRepo.save(delivery);
            }
            transferredStops.add(stopId);
        }

        // Validate Capacity if target is active
        validateCapacity(targetRoute, req.getAcknowledgeWarnings());

        sourceRoute.setRouteVersion(sourceRoute.getRouteVersion() != null ? sourceRoute.getRouteVersion() + 1 : 2);
        routeRepository.save(sourceRoute);

        if (sourceRoute.getStops().isEmpty()) {
            sourceRoute.setStatus(RouteStatus.CLOSED);
            sourceRoute.setClosedAt(LocalDateTime.now());
            routeRepository.save(sourceRoute);
            auditLogService.logAction(null, "CLOSE_ROUTE", "ROUTE", sourceRoute.getId().toString(),
                    Map.of("tournee", sourceRoute.getName(), "reason", "Empty after transfer"));
        }

        targetRoute.setRouteVersion(targetRoute.getRouteVersion() != null ? targetRoute.getRouteVersion() + 1 : 2);
        routeRepository.save(targetRoute);

        routeWebSocketService.notifyRouteUpdate(sourceRoute.getId());
        routeWebSocketService.notifyRouteUpdate(targetRoute.getId());
        
        auditLogService.logAction(null, "TRANSFER_STOPS", "ROUTE", sourceRoute.getId().toString(),
                Map.of("targetRoute", targetRoute.getId().toString(), "count", String.valueOf(transferredStops.size())));

        routeWebSocketService.notifyDriver(sourceRoute.getDriverId(), "STOPS_TRANSFERRED_OUT", sourceRoute.getId(), sourceRoute.getName());
        routeWebSocketService.notifyDriver(targetRoute.getDriverId(), "STOPS_TRANSFERRED_IN", targetRoute.getId(), targetRoute.getName());

        return com.asm.delivery.dto.response.TransferStopsResponse.builder()
                .targetRouteId(targetRoute.getId())
                .transferredStopIds(transferredStops)
                .sourceRouteStatus(sourceRoute.getStatus().name())
                .warnings(warnings)
                .build();
    }

    /**
     * Enforces vehicle payload and volume constraints.
     * Throws AppException if constraints are breached and not acknowledged.
     */
    public void validateCapacity(Route route, boolean acknowledge) {
        if (route.getStatus() == RouteStatus.DRAFT || route.getVehicleId() == null) {
            return;
        }

        Vehicle vehicle = vehicleRepository.findById(route.getVehicleId()).orElse(null);
        if (vehicle == null) return;

        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());
        if (stops.isEmpty()) return;

        List<UUID> deliveryIds = stops.stream()
                .filter(s -> !s.getStatus().name().startsWith("REMOVED"))
                .map(RouteStop::getDeliveryId)
                .toList();
        
        List<Delivery> deliveries = deliveryRepo.findAllByIdInWithOrder(deliveryIds);
        
        BigDecimal totalWeight = BigDecimal.ZERO;

        for (Delivery d : deliveries) {
            if (d.getOrder() != null) {
                if (d.getOrder().getTotalWeightKg() != null) {
                    totalWeight = totalWeight.add(d.getOrder().getTotalWeightKg());
                }
            }
        }

        // Weight Check
        if (vehicle.getPayloadKg() != null && vehicle.getPayloadKg() > 0) {
            if (totalWeight.compareTo(BigDecimal.valueOf(vehicle.getPayloadKg())) > 0 && !acknowledge) {
                throw AppException.unprocessableEntity(String.format(
                        "Weight limit exceeded: Vehicle %dkg vs Route %.2fkg", 
                        vehicle.getPayloadKg(), totalWeight.doubleValue()));
            }
        }
    }
}
