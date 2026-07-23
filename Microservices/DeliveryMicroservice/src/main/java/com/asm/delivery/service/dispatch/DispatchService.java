package com.asm.delivery.service.dispatch;

import com.asm.delivery.entity.Order;

import java.time.LocalDateTime;

import com.asm.delivery.dto.request.AssignDeliveryRequest;
import com.asm.delivery.dto.request.PinDropoffRequest;
import com.asm.delivery.dto.response.AdminDeliveryDetailResponse;
import com.asm.delivery.dto.response.AdminDeliverySummaryResponse;
import com.asm.delivery.dto.response.AdminDriverResponse;
import com.asm.delivery.dto.response.ActiveMissionsDTO;
import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import com.asm.delivery.entity.*;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.repository.*;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.service.DriverDeliveryService;
import com.asm.delivery.service.EventPublisher;
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
    private final com.asm.delivery.repository.RmaRepository rmaRepo;
    private final ProofOfDeliveryRepository podRepo;
    private final DriverDeliveryService driverDeliveryService;
    private final EntityManager entityManager;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final ZoneRepository zoneRepository;
    private final com.asm.delivery.repository.DepotRepository depotRepository;
    private final GeocodingService geocodingService;
    private final VehicleRepository vehicleRepository;
    private final com.asm.delivery.service.route.RouteWebSocketService routeWebSocketService;
    private final com.asm.delivery.service.route.PickupStopReconciler pickupStopReconciler;
    private final com.asm.delivery.service.AuditLogService auditLogService;
    private final EventPublisher eventPublisher;
    private final com.asm.delivery.service.HandoffService handoffService;
    private final com.asm.delivery.sla.SlaStateRepository slaStateRepository;
    private final com.asm.delivery.web.ActorNameResolver actorNameResolver;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ── Search deliveries ─────────────────────────────────────────────────────

        @Transactional(readOnly = true)
        public Page<AdminDeliverySummaryResponse> searchDeliveries(
            List<DeliveryStatus> statuses,
            List<UUID> driverIds,
            LocalDate date,
            List<OrderSource> sources,
            List<UUID> zoneIds,
            List<UUID> depotIds,
            Boolean unpinned,
            String q,
            Boolean assigned,
            String bucket,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<DeliveryKind> kinds,
            Pageable pageable
    ) {
        Page<Delivery> deliveryPage = doSearch(statuses, driverIds, date, sources, zoneIds, depotIds, unpinned, q, assigned, bucket, dateFrom, dateTo, kinds, pageable);
        List<Delivery> deliveries = deliveryPage.getContent();

        // Bulk-fetch driver info from Driver Service (OUTSIDE Transaction)
        Map<String, DriverDTO> driverMap = loadDriverMap(deliveries);
        Map<UUID, RouteInfo> routeInfoByDeliveryId = loadRouteInfoMap(deliveries);
        Map<UUID, Zone> zoneMap = loadZoneMap(deliveries);
        Map<UUID, com.asm.delivery.sla.SlaState> slaMap = loadSlaMap(deliveries);
        ReturnBatch returnBatch = loadReturnBatch(deliveries);

        List<AdminDeliverySummaryResponse> content = deliveries.stream()
                .map(d -> {
                    DriverDTO driver = d.getDriverId() != null ? driverMap.get(d.getDriverId().toString()) : null;
                    return toSummaryResponse(d, driver, routeInfoByDeliveryId.get(d.getId()), zoneMap, slaMap, returnBatch);
                })
                .toList();

        return new PageImpl<>(content, pageable, deliveryPage.getTotalElements());
    }

    /** Deliveries scheduled within [from, to] for the calendar/overview month view. */
    @Transactional(readOnly = true)
    public List<AdminDeliverySummaryResponse> calendar(LocalDate from, LocalDate to) {
        List<Delivery> deliveries = deliveryRepo.findScheduledBetween(from.atStartOfDay(), to.atTime(23, 59, 59));
        Map<String, DriverDTO> driverMap = loadDriverMap(deliveries);
        Map<UUID, RouteInfo> routeInfoByDeliveryId = loadRouteInfoMap(deliveries);
        Map<UUID, Zone> zoneMap = loadZoneMap(deliveries);
        Map<UUID, com.asm.delivery.sla.SlaState> slaMap = loadSlaMap(deliveries);
        ReturnBatch returnBatch = loadReturnBatch(deliveries);
        return deliveries.stream()
                .map(d -> {
                    DriverDTO driver = d.getDriverId() != null ? driverMap.get(d.getDriverId().toString()) : null;
                    return toSummaryResponse(d, driver, routeInfoByDeliveryId.get(d.getId()), zoneMap, slaMap, returnBatch);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<Delivery> doSearch(List<DeliveryStatus> statuses, List<UUID> driverIds, LocalDate date, List<OrderSource> sources,
                                  List<UUID> zoneIds, List<UUID> depotIds, Boolean unpinned, String q, Boolean assigned, String bucket,
                                  LocalDate dateFrom, LocalDate dateTo, List<DeliveryKind> kinds,
                                  Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Delivery> cq = cb.createQuery(Delivery.class);
        Root<Delivery> root = cq.from(Delivery.class);
        root.fetch("order", JoinType.INNER);
        List<Predicate> predicates = buildPredicates(cb, root, statuses, driverIds, date, sources, zoneIds, depotIds, unpinned, q, assigned, bucket, dateFrom, dateTo, kinds);
        cq.select(root).distinct(true).where(predicates.toArray(Predicate[]::new))
                .orderBy(cb.desc(root.get("createdAt")));

        TypedQuery<Delivery> query = entityManager.createQuery(cq);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        List<Delivery> deliveries = query.getResultList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<Delivery> countRoot = countQuery.from(Delivery.class);
        List<Predicate> countPredicates = buildPredicates(cb, countRoot, statuses, driverIds, date, sources, zoneIds, depotIds, unpinned, q, assigned, bucket, dateFrom, dateTo, kinds);
        countQuery.select(cb.count(countRoot)).where(countPredicates.toArray(Predicate[]::new));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(deliveries, pageable, total);
    }

    // ── Delivery history ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StatusHistoryResponse> getDeliveryHistory(UUID id) {
        List<DeliveryStatusHistory> histories = getHistoryRaw(id);
        Map<String, String> actorNames = fetchActorNames(histories);
        return histories.stream()
                .map(h -> toHistoryResponseLocal(h, actorNames))
                .toList();
    }

    // ── Delivery detail ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminDeliveryDetailResponse getDeliveryDetail(UUID id) {
        Delivery delivery = findDelivery(id);
        RouteInfo routeInfo = getRouteInfo(id);

        DriverDTO driver = null;
        if (delivery.getDriverId() != null) {
            // HTTP call OUTSIDE transaction
            driver = transportPort.getDriver(delivery.getDriverId().toString());
        }

        List<StatusHistoryResponse> history = getHistory(delivery.getId());
        boolean podExists = checkPodExists(delivery.getId());

        return toDetailResponse(delivery, driver, history, podExists, routeInfo);
    }

    @Transactional(readOnly = true)
    public RouteInfo getRouteInfo(UUID deliveryId) {
        return routeStopRepository.findActiveByDeliveryIdWithRoute(deliveryId)
                .filter(rs -> rs.getRoute() != null)
                .map(rs -> new RouteInfo(rs.getRoute().getId(), rs.getRoute().getName(),
                        rs.getRoute().getStatus() != null ? rs.getRoute().getStatus().name() : null,
                        rs.getStartTimeWindow(), rs.getEndTimeWindow()))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Delivery findDelivery(UUID id) {
        return deliveryRepo.findByIdWithOrder(id)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));
    }

    @Transactional(readOnly = true)
    public List<StatusHistoryResponse> getHistory(UUID deliveryId) {
        List<DeliveryStatusHistory> histories = getHistoryRaw(deliveryId);
        Map<String, String> actorNames = fetchActorNames(histories);
        return histories.stream()
                .map(h -> toHistoryResponseLocal(h, actorNames))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DeliveryStatusHistory> getHistoryRaw(UUID deliveryId) {
        return historyRepo.findByDeliveryIdOrderByChangedAtAsc(deliveryId);
    }

    private Map<String, String> fetchActorNames(List<DeliveryStatusHistory> histories) {
        return actorNameResolver.prefetch(histories);
    }

    @Transactional(readOnly = true)
    public boolean checkPodExists(UUID deliveryId) {
        return podRepo.existsByDeliveryId(deliveryId);
    }

    // ── Drivers list ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AdminDriverResponse> getDrivers() {
        // 1. Fetch available drivers (HTTP)
        List<DriverDTO> drivers = new ArrayList<>(transportPort.getAvailableDrivers());

        // 2. Fetch active IDs from DB (Transactional)
        DriverData data = getActiveDriverData();

        // 3. Ensure busy drivers appear (HTTP calls for missing drivers)
        java.util.Set<String> knownDriverIds = drivers.stream().map(DriverDTO::getId).collect(Collectors.toSet());
        for (String driverId : data.activeDeliveryMap().keySet()) {
            if (!knownDriverIds.contains(driverId)) {
                DriverDTO busyDriver = transportPort.getDriver(driverId);
                if (busyDriver != null) {
                    drivers.add(busyDriver);
                    knownDriverIds.add(driverId);
                }
            }
        }
        for (String driverId : data.activeRouteMap().keySet()) {
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
                        .activeDeliveryId(data.activeDeliveryMap().get(d.getId()))
                        .activeRouteId(data.activeRouteMap().get(d.getId()))
                        .createdAt(d.getCreatedAt() != null ? parseDateTime(d.getCreatedAt()) : null)
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public DriverData getActiveDriverData() {
        Map<String, UUID> activeDeliveryMap = deliveryRepo.findActiveDeliveries(ACTIVE_STATUSES).stream()
                .filter(d -> d.getDriverId() != null)
                .collect(Collectors.toMap(
                        d -> d.getDriverId().toString(),
                        Delivery::getId,
                        (existing, replacement) -> existing
                ));

        LocalDate today = LocalDate.now();
        // Query today's active routes directly instead of scanning every route ever (hot path on the
        // live dispatch desk).
        Map<String, UUID> activeRouteMap = routeRepository
                .findByDateAndStatusIn(today, List.of(RouteStatus.VALIDATED, RouteStatus.IN_PROGRESS)).stream()
                .filter(route -> route.getDriverId() != null)
                .collect(Collectors.toMap(
                        route -> route.getDriverId().toString(),
                        Route::getId,
                        (existing, replacement) -> existing
                ));
        
        return new DriverData(activeDeliveryMap, activeRouteMap);
    }

    @Transactional(readOnly = true)
    public Map<UUID, ActiveMissionsDTO> getActiveMissions() {
        DriverData data = getActiveDriverData();
        Map<UUID, ActiveMissionsDTO> result = new HashMap<>();
        
        Set<String> driverIds = new HashSet<>();
        driverIds.addAll(data.activeDeliveryMap().keySet());
        driverIds.addAll(data.activeRouteMap().keySet());
        
        for (String id : driverIds) {
            try {
                UUID driverUuid = UUID.fromString(id);
                result.put(driverUuid, ActiveMissionsDTO.builder()
                        .activeDeliveryId(data.activeDeliveryMap().get(id))
                        .activeRouteId(data.activeRouteMap().get(id))
                        .build());
            } catch (Exception ignored) {}
        }
        return result;
    }

    private record DriverData(Map<String, UUID> activeDeliveryMap, Map<String, UUID> activeRouteMap) {}
    // ── Assign ────────────────────────────────────────────────────────────────

    // Removed @Transactional so the DB write lock drops before the HTTP read in getDeliveryDetail
    public AdminDeliveryDetailResponse assignDelivery(UUID deliveryId, AssignDeliveryRequest request, UserPrincipal principal) {
        driverDeliveryService.accept(deliveryId, request.getDriverId(), principal);
        return getDeliveryDetail(deliveryId);
    }

    @Transactional
    public void syncAllZones() {
        // Maintenance re-zone of every delivery. Process in bounded pages so we never hold the entire
        // deliveries table in memory at once (it grows without limit).
        final int pageSize = 500;
        int pageNum = 0;
        org.springframework.data.domain.Page<Delivery> page;
        do {
            page = deliveryRepo.findAll(org.springframework.data.domain.PageRequest.of(
                    pageNum, pageSize, org.springframework.data.domain.Sort.by("id")));
            for (Delivery delivery : page.getContent()) {
                Order order = delivery.getOrder();
                if (order == null) continue;

                String postalCode = normalizePostalCode(order.getDropoffPostalCode());
                String city = normalizeText(order.getDropoffCity());

                UUID newZoneId = null;
                boolean found = false;

                if (StringUtils.hasText(postalCode)) {
                    Optional<Zone> zoneByPostal = zoneRepository.findActiveByPostalCodeMember(postalCode);
                    if (zoneByPostal.isPresent()) {
                        newZoneId = zoneByPostal.get().getId();
                        found = true;
                    }
                }

                if (!found && StringUtils.hasText(city)) {
                    Optional<Zone> zoneByCity = zoneRepository.findActiveByCityMember(city.trim());
                    if (zoneByCity.isPresent()) {
                        newZoneId = zoneByCity.get().getId();
                    }
                }

                if ((order.getZoneId() == null && newZoneId != null) ||
                    (order.getZoneId() != null && !order.getZoneId().equals(newZoneId))) {
                    order.setZoneId(newZoneId);
                    orderRepo.save(order);
                }
            }
            pageNum++;
        } while (page.hasNext());
    }

    public void pinDropoff(UUID deliveryId, PinDropoffRequest request) {
        // 1. External Geocoding (Outside Transaction)
        GeocodeSuggestionResponse reverse = geocodingService.reverseGeocode(
                request.getLat().doubleValue(),
                request.getLng().doubleValue()
        );

        // 2. Transactional Update
        updatePin(deliveryId, request, reverse);
    }

    @Transactional
    public void updatePin(UUID deliveryId, PinDropoffRequest request, GeocodeSuggestionResponse reverse) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        // Lock check: cannot re-pin once the order is picked up or beyond
        if (delivery.getStatus() == DeliveryStatus.PICKED_UP
                || delivery.getStatus() == DeliveryStatus.IN_TRANSIT
                || delivery.getStatus() == DeliveryStatus.DELIVERED
                || delivery.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED
                || delivery.getStatus() == DeliveryStatus.FAILED) {
            throw AppException.badRequest("Pin locked: order is in transit or completed.");
        }

        Order order = delivery.getOrder();
        if (order == null) throw AppException.badRequest("Delivery has no order attached");

        order.setDropoffLat(request.getLat());
        order.setDropoffLng(request.getLng());

        String requestedAddress = normalizeText(request.getDropoffAddress());
        String requestedCity = normalizeText(request.getDropoffCity());
        String requestedPostalCode = normalizePostalCode(request.getDropoffPostalCode());
        String requestedCountryCode = normalizeText(request.getDropoffCountryCode());

        if (StringUtils.hasText(request.getDropoffAddress())) order.setDropoffAddress(requestedAddress);
        if (StringUtils.hasText(request.getDropoffCity())) order.setDropoffCity(requestedCity);
        if (StringUtils.hasText(request.getDropoffPostalCode())) order.setDropoffPostalCode(requestedPostalCode);
        if (StringUtils.hasText(request.getDropoffCountryCode())) order.setDropoffCountryCode(requestedCountryCode);

        // Robust fallback: enrich missing fields from pin coordinates.
        if (!StringUtils.hasText(requestedAddress) && reverse != null && StringUtils.hasText(reverse.getDisplayName())) {
            order.setDropoffAddress(reverse.getDisplayName().trim());
        }
        if (!StringUtils.hasText(requestedCity) && reverse != null && StringUtils.hasText(reverse.getCity())) {
            order.setDropoffCity(reverse.getCity().trim());
        }
        if (!StringUtils.hasText(requestedPostalCode) && reverse != null && StringUtils.hasText(reverse.getPostalCode())) {
            order.setDropoffPostalCode(normalizePostalCode(reverse.getPostalCode()));
        }

        // Zone detection
        String postalCode = normalizePostalCode(order.getDropoffPostalCode());
        String city = normalizeText(order.getDropoffCity());
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

        // Mark associated route as needing recalculation
        routeStopRepository.findActiveByDeliveryIdWithRoute(deliveryId).ifPresent(stop -> {
            Route route = stop.getRoute();
            if (route != null && (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)) {
                route.setIsOptimized(false);
                routeRepository.save(route);
            }
        });

        // Audit: an operator manually pinned the dropoff location (actor from SecurityContext).
        auditLogService.logAction(null, "PIN_DROPOFF", "DELIVERY", deliveryId.toString(),
                java.util.Map.of(
                        "client", order.getClientName() != null ? order.getClientName() : "",
                        "lat", String.valueOf(request.getLat()),
                        "lng", String.valueOf(request.getLng()),
                        "address", order.getDropoffAddress() != null ? order.getDropoffAddress() : ""));
    }
    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final List<DeliveryStatus> TERMINAL_STATUSES = List.of(
            DeliveryStatus.DELIVERED, DeliveryStatus.PARTIALLY_DELIVERED,
            DeliveryStatus.FAILED, DeliveryStatus.CANCELLED);

    private static boolean has(java.util.Collection<?> c) { return c != null && !c.isEmpty(); }

    private List<Predicate> buildPredicates(CriteriaBuilder cb,
                                            Root<Delivery> root,
                                            List<DeliveryStatus> statuses,
                                            List<UUID> driverIds,
                                            LocalDate date,
                                            List<OrderSource> sources,
                                            List<UUID> zoneIds,
                                            List<UUID> depotIds,
                                            Boolean unpinned,
                                            String q,
                                            Boolean assigned,
                                            String bucket,
                                            LocalDate dateFrom,
                                            LocalDate dateTo,
                                            List<DeliveryKind> kinds) {
        List<Predicate> predicates = new ArrayList<>();
        if (has(statuses)) {
            predicates.add(root.get("status").in(statuses));
        }
        if (has(kinds)) {
            predicates.add(root.get("kind").in(kinds));
        }
        if (has(driverIds)) {
            predicates.add(root.get("driverId").in(driverIds));
        }
        if (has(depotIds)) {
            predicates.add(root.get("sourceDepotId").in(depotIds));
        }
        if (assigned != null) {
            predicates.add(assigned ? cb.isNotNull(root.get("driverId")) : cb.isNull(root.get("driverId")));
        }

        String trimmedQ = (q == null) ? null : q.trim();
        boolean hasQ = trimmedQ != null && !trimmedQ.isEmpty();
        boolean hasBucket = bucket != null && !bucket.isBlank();
        boolean hasRange = dateFrom != null || dateTo != null;
        // Date filter on Planifié (effective scheduledAt = rescheduledAt ?? scheduledAt)
        boolean needsOrderJoin = date != null || hasRange || has(sources) || has(zoneIds)
                || Boolean.TRUE.equals(unpinned) || hasQ || hasBucket;
        if (needsOrderJoin) {
            Join<Delivery, Order> orderJoin = root.join("order", JoinType.INNER);
            if (date != null) {
                LocalDateTime start = date.atStartOfDay();
                LocalDateTime end = start.plusDays(1);
                predicates.add(cb.or(
                    cb.between(orderJoin.get("scheduledAt"), start, end),
                    cb.between(orderJoin.get("rescheduledAt"), start, end)
                ));
            }
            // De/A range on the effective scheduled date (Planifié). Either bound is optional:
            // dateFrom only → from that day onward; dateTo only → up to and including that day.
            if (hasRange) {
                var effSched = cb.coalesce(orderJoin.<LocalDateTime>get("rescheduledAt"),
                                           orderJoin.<LocalDateTime>get("scheduledAt"));
                if (dateFrom != null) {
                    predicates.add(cb.greaterThanOrEqualTo(effSched, dateFrom.atStartOfDay()));
                }
                if (dateTo != null) {
                    predicates.add(cb.lessThan(effSched, dateTo.plusDays(1).atStartOfDay()));
                }
            }
            if (has(sources)) {
                predicates.add(orderJoin.get("source").in(sources));
            }
            if (has(zoneIds)) {
                predicates.add(orderJoin.get("zoneId").in(zoneIds));
            }
            if (Boolean.TRUE.equals(unpinned)) {
                predicates.add(cb.isNull(orderJoin.get("dropoffLat")));
            }
            if (hasQ) {
                String pattern = "%" + trimmedQ.toLowerCase() + "%";
                predicates.add(cb.or(
                    cb.like(cb.lower(orderJoin.<String>get("clientName")), pattern),
                    cb.like(cb.lower(orderJoin.<String>get("dropoffCity")), pattern),
                    cb.like(cb.lower(orderJoin.<String>get("erpOrderId")), pattern),
                    cb.like(cb.lower(root.<String>get("blNumber")), pattern)
                ));
            }
            if (hasBucket) {
                LocalDateTime todayStart = LocalDate.now().atStartOfDay();
                LocalDateTime tomorrowStart = todayStart.plusDays(1);
                var effSched = cb.coalesce(orderJoin.<LocalDateTime>get("rescheduledAt"),
                                           orderJoin.<LocalDateTime>get("scheduledAt"));
                Predicate pending = cb.not(root.get("status").in(TERMINAL_STATUSES));
                switch (bucket.toUpperCase()) {
                    case "OVERDUE" -> predicates.add(cb.and(pending, cb.lessThan(effSched, todayStart)));
                    case "TODAY"   -> predicates.add(cb.and(pending,
                            cb.greaterThanOrEqualTo(effSched, todayStart), cb.lessThan(effSched, tomorrowStart)));
                    case "FUTURE"  -> predicates.add(cb.and(pending, cb.greaterThanOrEqualTo(effSched, tomorrowStart)));
                    case "FAILED"  -> predicates.add(root.get("status").in(
                            List.of(DeliveryStatus.FAILED, DeliveryStatus.CANCELLED)));
                    default -> { /* unknown bucket -> ignore */ }
                }
            }
        }
        return predicates;
    }

    /**
     * Quick-view tallies under the current base filters (driver / date / source / zone / search),
     * computed across the WHOLE dataset — so the deliveries sidebar is accurate, not per-page.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> deliveryCounts(UUID driverId, LocalDate date, OrderSource source,
                                            UUID zoneId, String q) {
        Map<String, Long> m = new HashMap<>();
        m.put("all",          countTally(null, driverId, date, source, zoneId, q, null, null, null));
        m.put("needsPinning", countTally(null, driverId, date, source, zoneId, q, null, null, Boolean.TRUE));
        m.put("unassigned",   countTally(null, driverId, date, source, zoneId, q, Boolean.FALSE, null, null));
        m.put("inTransit",    countTally(DeliveryStatus.IN_TRANSIT, driverId, date, source, zoneId, q, null, null, null)
                            + countTally(DeliveryStatus.AWAITING_HANDOFF, driverId, date, source, zoneId, q, null, null, null));
        m.put("completed",    countTally(DeliveryStatus.DELIVERED, driverId, date, source, zoneId, q, null, null, null));
        m.put("failed",       countTally(DeliveryStatus.FAILED, driverId, date, source, zoneId, q, null, null, null)
                            + countTally(DeliveryStatus.CANCELLED, driverId, date, source, zoneId, q, null, null, null));
        m.put("overdue",      countTally(null, driverId, date, source, zoneId, q, null, "OVERDUE", null));
        m.put("today",        countTally(null, driverId, date, source, zoneId, q, null, "TODAY", null));
        m.put("future",       countTally(null, driverId, date, source, zoneId, q, null, "FUTURE", null));
        return m;
    }

    private long countTally(DeliveryStatus status, UUID driverId, LocalDate date, OrderSource source,
                            UUID zoneId, String q, Boolean assigned, String bucket, Boolean unpinned) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<Delivery> root = cq.from(Delivery.class);
        List<Predicate> ps = buildPredicates(cb, root,
                status != null ? List.of(status) : null,
                driverId != null ? List.of(driverId) : null,
                date,
                source != null ? List.of(source) : null,
                zoneId != null ? List.of(zoneId) : null,
                null,
                unpinned, q, assigned, bucket, null, null, null);
        cq.select(cb.count(root)).where(ps.toArray(Predicate[]::new));
        return entityManager.createQuery(cq).getSingleResult();
    }

    /** Bulk-fetch all unique drivers needed for a list of deliveries (single batch HTTP call). */
    private Map<String, DriverDTO> loadDriverMap(List<Delivery> deliveries) {
        Map<String, DriverDTO> map = new HashMap<>();
        try {
            for (DriverDTO d : transportPort.getAvailableDrivers()) {
                if (d.getId() != null) map.put(d.getId(), d);
            }
        } catch (Exception e) {
            // fallback: empty map — driver names will be null
        }
        // Also pick up drivers not in the available list (e.g. deactivated) via individual calls
        deliveries.stream()
                .map(Delivery::getDriverId)
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .filter(id -> !map.containsKey(id))
                .distinct()
                .forEach(id -> {
                    DriverDTO dto = transportPort.getDriver(id);
                    if (dto != null) map.put(id, dto);
                });
        return map;
    }

    /** Batch-fetch all zones needed for a list of deliveries. */
    private Map<UUID, Zone> loadZoneMap(List<Delivery> deliveries) {
        Set<UUID> zoneIds = deliveries.stream()
                .map(Delivery::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getZoneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (zoneIds.isEmpty()) return Map.of();
        return zoneRepository.findAll().stream()
                .filter(z -> zoneIds.contains(z.getId()))
                .collect(Collectors.toMap(Zone::getId, z -> z));
    }

    /** Batch-fetch all SLA states needed for a list of deliveries. */
    private Map<UUID, com.asm.delivery.sla.SlaState> loadSlaMap(List<Delivery> deliveries) {
        List<UUID> deliveryIds = deliveries.stream()
                .map(Delivery::getId)
                .filter(Objects::nonNull)
                .toList();
        if (deliveryIds.isEmpty()) return Map.of();
        return slaStateRepository.findByDeliveryIdIn(deliveryIds).stream()
                .collect(Collectors.toMap(com.asm.delivery.sla.SlaState::getDeliveryId, s -> s));
    }

    @Transactional(readOnly = true)
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
                        routeStop -> new RouteInfo(routeStop.getRoute().getId(), routeStop.getRoute().getName(),
                                routeStop.getRoute().getStatus() != null ? routeStop.getRoute().getStatus().name() : null,
                                routeStop.getStartTimeWindow(), routeStop.getEndTimeWindow()),
                        (existing, replacement) -> existing
                ));
    }

    /**
     * Worst health any lifecycle phase ever reached, read from the persisted {@code phaseHealth} map.
     * Lets a terminal delivery (whose live health is NONE for FAILED/CANCELLED, or MET/LATE for done)
     * still report that an earlier phase was BREACHED/AT_RISK — i.e. "it was late before it failed".
     * Returns null when no phase history was recorded or nothing exceeded ON_TRACK.
     */
    private String worstPhaseHealth(com.asm.delivery.sla.SlaState s) {
        if (s == null || s.getPhaseHealth() == null || s.getPhaseHealth().isEmpty()) return null;
        // Severity rank: BREACHED/LATE worst, then AT_RISK, then the rest (ignored).
        String worst = null;
        int worstRank = 0;
        for (String h : s.getPhaseHealth().values()) {
            int rank = switch (h == null ? "" : h) {
                case "BREACHED", "LATE" -> 3;
                case "AT_RISK"          -> 2;
                default                 -> 0;
            };
            if (rank > worstRank) { worstRank = rank; worst = h; }
        }
        return worst; // null if every phase was ON_TRACK/MET/NONE
    }

    private AdminDeliverySummaryResponse toSummaryResponse(Delivery d, DriverDTO driver, RouteInfo routeInfo,
                                                             Map<UUID, Zone> zoneMap, Map<UUID, com.asm.delivery.sla.SlaState> slaMap,
                                                             ReturnBatch returnBatch) {
        Order order = d.getOrder();
        // ADR-033 — a return collection shows the RMA lines (returned qty) + its own RMA ref, not the shared order.
        boolean isReturn = d.getKind() == DeliveryKind.RETURN_PICKUP;
        List<OrderItem> lines = isReturn
                ? returnBatch.items().getOrDefault(d.getId(), List.of())
                : (order != null ? order.getItems() : null);
        String rmaNumber = isReturn ? returnBatch.numbers().get(d.getId()) : null;
        boolean isDropoffPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;
        Zone zone = (order != null && order.getZoneId() != null)
                ? zoneMap.get(order.getZoneId())
                : null;
        com.asm.delivery.sla.SlaState slaState = slaMap.get(d.getId());
        return AdminDeliverySummaryResponse.builder()
                .slaPhase(slaState != null && slaState.getPhase() != null ? slaState.getPhase().name() : null)
                .slaHealth(slaState != null && slaState.getHealth() != null ? slaState.getHealth().name() : null)
                .slaWorstHealth(worstPhaseHealth(slaState))
                .slaLateMinutes(slaState != null ? slaState.getLateMinutes() : null)
                .deliveryId(d.getId())
                .orderId(order != null ? order.getId() : null)
                .orderRef(order != null ? order.resolveRef() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                .routeStatus(routeInfo != null ? routeInfo.routeStatus() : null)
                .timeSlotStartTime(routeInfo != null && routeInfo.startWindow() != null ? routeInfo.startWindow().toString() : null)
                .timeSlotEndTime(routeInfo != null && routeInfo.endWindow() != null ? routeInfo.endWindow().toString() : null)
                .status(d.getStatus().name())
                .kind(d.getKind() != null ? d.getKind().name() : "FORWARD")
                .rmaNumber(rmaNumber)
                .failureCode(d.getFailureCode() != null ? d.getFailureCode().name() : null)
                .failReason(d.getFailReason())
                .source(order != null ? order.getSource() : null)
                .warehouseCode(order != null ? order.getWarehouseCode() : null)
                .sourceDepotId(d.getSourceDepotId() != null ? d.getSourceDepotId()
                        : (order != null ? order.getSourceDepotId() : null))
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
                .currency(order != null ? order.getCurrency() : null)
                .totalWeightKg(order != null ? order.getTotalWeightKg() : null)
                .routeDistanceKm(d.getRouteDistanceKm())
                .routeDurationMinutes(d.getRouteDurationMinutes())
                .transitSlaMinutesComputed(d.getTransitSlaMinutesComputed())
                .routeEtaAt(d.getRouteEtaAt())
                .routeGeometry(d.getRouteGeometry())
                .routeProvider(d.getRouteProvider())
                .scheduledAt(order != null ? order.effectiveScheduledAt() : null)
                .rescheduledAt(order != null ? order.getRescheduledAt() : null)
                .createdAt(d.getCreatedAt())
                .assignedAt(d.getAssignedAt())
                .inTransitAt(d.getInTransitAt())
                .completedAt(d.getCompletedAt())
                .failedAt(d.getFailedAt())
                .cancelledAt(d.getCancelledAt())
                .updatedAt(d.getUpdatedAt())
                .totalQuantity(isReturn
                    ? sumQty(lines)
                    : (order != null ? (order.getTotalQuantity() != null && order.getTotalQuantity() > 0
                        ? order.getTotalQuantity()
                        : sumQty(order.getItems())) : null))
                .itemsSummary(lines != null
                    ? lines.stream()
                        .map(i -> i.getQuantity() + "x " + (i.getName() != null ? i.getName() : "Item"))
                        .collect(Collectors.joining(", "))
                    : null)
                .items(lines != null ? new ArrayList<>(lines) : null)
                .build();
    }

    /** Sum of line quantities, null-safe. */
    private static int sumQty(List<OrderItem> items) {
        if (items == null) return 0;
        return items.stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();
    }

    /** Prefetched return data for a batch of deliveries, keyed by delivery id (empty for non-returns). */
    private record ReturnBatch(Map<UUID, List<OrderItem>> items, Map<UUID, String> numbers) {}

    /**
     * ADR-033 — Prefetch return data (line items + RMA reference, keyed by delivery id) for every
     * RETURN_PICKUP in the batch, in a single findAllById — so the summary list never does a per-row RMA
     * lookup. Empty maps when the batch has no returns.
     */
    private ReturnBatch loadReturnBatch(List<Delivery> deliveries) {
        Map<UUID, UUID> deliveryToRma = new HashMap<>();
        for (Delivery d : deliveries) {
            if (d.getKind() == DeliveryKind.RETURN_PICKUP && d.getRmaId() != null) {
                deliveryToRma.put(d.getId(), d.getRmaId());
            }
        }
        if (deliveryToRma.isEmpty()) return new ReturnBatch(Map.of(), Map.of());
        Map<UUID, List<OrderItem>> itemsByRma = new HashMap<>();
        Map<UUID, String> numberByRma = new HashMap<>();
        for (Rma r : rmaRepo.findAllById(deliveryToRma.values())) {
            itemsByRma.put(r.getId(), com.asm.delivery.service.RmaService.toOrderItems(r.getItems()));
            numberByRma.put(r.getId(), r.getRmaNumber());
        }
        Map<UUID, List<OrderItem>> items = new HashMap<>();
        Map<UUID, String> numbers = new HashMap<>();
        deliveryToRma.forEach((deliveryId, rmaId) -> {
            items.put(deliveryId, itemsByRma.getOrDefault(rmaId, List.of()));
            numbers.put(deliveryId, numberByRma.get(rmaId));
        });
        return new ReturnBatch(items, numbers);
    }

        private record RouteInfo(UUID routeId, String routeName, String routeStatus,
                                 java.time.LocalTime startWindow, java.time.LocalTime endWindow) {}

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
    /**
     * The admin failure motif label, without the driver's appended comment. fail_reason is stored
     * flattened as "label — comment"; because the comment is now also persisted on its own field, we
     * can strip it back off reliably (the naive " — " split is ambiguous — the admin label itself may
     * contain " — "). Legacy rows (no failure_comment) return fail_reason unchanged.
     */
    private static String failureMotifLabel(Delivery d) {
        String reason = d.getFailReason();
        String comment = d.getFailureComment();
        if (reason != null && comment != null && !comment.isBlank()) {
            String suffix = " — " + comment.trim();
            if (reason.endsWith(suffix)) return reason.substring(0, reason.length() - suffix.length());
        }
        return reason;
    }

    private AdminDeliveryDetailResponse toDetailResponse(Delivery d,
                                                         DriverDTO driver,
                                                         List<StatusHistoryResponse> history,
                                                         boolean podExists,
                                                         RouteInfo routeInfo) {
        Order order = d.getOrder();
        boolean isDropoffPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;
        Zone zone = (order != null && order.getZoneId() != null)
                ? zoneRepository.findById(order.getZoneId()).orElse(null)
                : null;

        UUID detailSrcDepotId = order != null ? order.getSourceDepotId() : null;
        String detailSourceDepotName = detailSrcDepotId != null
                ? depotRepository.findById(detailSrcDepotId).map(Depot::getName).orElse(null)
                : null;

        // ADR-033 — a return collection shows the RMA lines (returned qty) + its own RMA ref, not the shared order.
        boolean isReturnDetail = d.getKind() == DeliveryKind.RETURN_PICKUP && d.getRmaId() != null;
        Rma detailRma = isReturnDetail ? rmaRepo.findById(d.getRmaId()).orElse(null) : null;
        List<OrderItem> detailLines = detailRma != null
                ? com.asm.delivery.service.RmaService.toOrderItems(detailRma.getItems())
                : (order != null ? order.getItems() : null);
        String detailRmaNumber = detailRma != null ? detailRma.getRmaNumber() : null;
        UUID originalDeliveryId = detailRma != null ? detailRma.getDeliveryId() : null;

        return AdminDeliveryDetailResponse.builder()
                .deliveryId(d.getId())
                .orderId(order != null ? order.getId() : null)
                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                .timeSlotStartTime(routeInfo != null && routeInfo.startWindow() != null ? routeInfo.startWindow().toString() : null)
                .timeSlotEndTime(routeInfo != null && routeInfo.endWindow() != null ? routeInfo.endWindow().toString() : null)
                .status(d.getStatus().name())
                .kind(d.getKind() != null ? d.getKind().name() : "FORWARD")
                .rmaNumber(detailRmaNumber)
                .originalDeliveryId(originalDeliveryId)
                .failureCode(d.getFailureCode() != null ? d.getFailureCode().name() : null)
                .failReason(failureMotifLabel(d))
                .failureComment(d.getFailureComment())
                .driverId(d.getDriverId())
                .driverName(driver != null ? driver.getName() : null)
                .driverPhone(driver != null ? driver.getPhone() : null)
                .source(order != null ? order.getSource() : null)
                .erpOrderId(order != null ? order.getErpOrderId() : null)
                .erpExternalRef(order != null ? order.getErpExternalRef() : null)
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
                .items(detailLines != null ? new ArrayList<>(detailLines) : null)
                .totalAmount(order != null ? order.getTotalAmount() : null)
                .totalWeightKg(order != null ? order.getTotalWeightKg() : null)
                .routeDistanceKm(d.getRouteDistanceKm())
                .routeDurationMinutes(d.getRouteDurationMinutes())
                .transitSlaMinutesComputed(d.getTransitSlaMinutesComputed())
                .routeEtaAt(d.getRouteEtaAt())
                .routeGeometry(d.getRouteGeometry())
                .routeProvider(d.getRouteProvider())
                .currency(order != null ? order.getCurrency() : null)
                .erpSyncStatus(order != null ? order.getErpSyncStatus() : null)
                .erpBackorderId(order != null ? order.getErpBackorderId() : null)
                .scheduledAt(order != null ? order.effectiveScheduledAt() : null)
                .rescheduledAt(order != null ? order.getRescheduledAt() : null)
                .blNumber(order != null ? order.getBlNumber() : null)
                .warehouseCode(order != null ? order.getWarehouseCode() : null)
                .sourceDepotId(order != null ? order.getSourceDepotId() : null)
                .sourceDepotName(detailSourceDepotName)
                .createdAt(d.getCreatedAt())
                .assignedAt(d.getAssignedAt())
                .pickedUpAt(d.getPickedUpAt())
                .inTransitAt(d.getInTransitAt())
                .completedAt(d.getCompletedAt())
                .failedAt(d.getFailedAt())
                .cancelledAt(d.getCancelledAt())
                .podExists(podExists)
                .statusHistory(history)
                .relatedShipments(buildRelatedShipments(order, d.getId()))
                .hasOpenReturn(hasOpenReturn(d.getId()))
                .build();
    }

    /** True when the delivery already has a non-terminal return — mirrors RmaService's open-RMA
     *  guard so the UI can prevent a duplicate before the operator even submits. */
    private boolean hasOpenReturn(UUID deliveryId) {
        return rmaRepo.findByDeliveryIdOrderByCreatedAtDesc(deliveryId).stream()
                .anyMatch(r -> com.asm.delivery.service.RmaService.OPEN_STATUSES.contains(r.getStatus()));
    }

    /**
     * All shipments sharing this order's sale-order ref (original + backorder(s) / multi-depot splits),
     * so the group is traceable from any one of them. Returns null when there is no group (a single
     * shipment) — the UI then shows nothing.
     */
    private List<AdminDeliveryDetailResponse.RelatedShipment> buildRelatedShipments(Order order, UUID currentDeliveryId) {
        if (order == null || !StringUtils.hasText(order.getErpExternalRef())) return null;
        List<Order> group = orderRepo.findByErpExternalRefOrderByCreatedAtAsc(order.getErpExternalRef());
        if (group.size() <= 1) return null;
        List<AdminDeliveryDetailResponse.RelatedShipment> out = new ArrayList<>();
        for (Order o : group) {
            Delivery del = deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(o.getId()).orElse(null);
            if (del == null) continue;
            out.add(AdminDeliveryDetailResponse.RelatedShipment.builder()
                    .deliveryId(del.getId())
                    .blNumber(o.getBlNumber())
                    .erpOrderId(o.getErpOrderId())          // fallback ref when no BL yet (ERPNext reliquat)
                    .status(del.getStatus().name())
                    .current(del.getId().equals(currentDeliveryId))
                    .build());
        }
        return out.size() > 1 ? out : null;
    }

    private Map<String, Object> deserializeEventParams(String json) {
        if (json == null || json.isEmpty()) return Map.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private StatusHistoryResponse toHistoryResponseLocal(DeliveryStatusHistory h, Map<String, String> actorNames) {
        String actorDisplay = actorNameResolver.resolve(h.getChangedBy(), h.getChangedByRole(), actorNames);
        return StatusHistoryResponse.builder()
                .id(h.getId() != null ? h.getId().toString() : null)
                .status(h.getStatus().name())
                .actor(actorDisplay)
                .timestamp(h.getChangedAt())
                .changedBy(actorDisplay)
                .changedByRole(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                .eventKey(h.getEventKey())
                .eventParams(deserializeEventParams(h.getEventParams()))
                .changedAt(h.getChangedAt())
                .build();
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
        List<UUID> handoffDeliveryIds = new ArrayList<>();

        int currentOrder = startOrder;
        UUID firstMovedDepot = null; // seeds a brand-new target route's home depot (ADR-029 parity)
        for (UUID stopId : req.getStopIds()) {
            RouteStop stop = sourceStopMap.get(stopId);
            if (stop == null) {
                throw AppException.badRequest("Stop " + stopId + " not found in source route");
            }

            RouteStopStatus status = stop.getStatus();
            if (status == RouteStopStatus.COMPLETED || status == RouteStopStatus.FAILED || status == RouteStopStatus.PARTIAL) {
                throw AppException.badRequest("Cannot transfer a completed or failed stop");
            }

            boolean inField = status == RouteStopStatus.PICKED_UP || status == RouteStopStatus.IN_TRANSIT;
            if (inField) {
                if (!StringUtils.hasText(req.getReason())) {
                    throw AppException.badRequest("Reason is required when transferring picked up packages");
                }
                // Formal custody handoff opened after the stop is moved (see below).
                handoffDeliveryIds.add(stop.getDeliveryId());

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
                if (firstMovedDepot == null && delivery.getSourceDepotId() != null) {
                    firstMovedDepot = delivery.getSourceDepotId();
                }
                delivery.setDriverId(targetRoute.getDriverId());
                if (inField) {
                    // In-field parcel: custody transfers by handoff, so it never rewinds to SCHEDULED —
                    // it stays "in someone's hands". AWAITING_HANDOFF is the honest transition; the
                    // handoff-confirm re-advances it to PICKED_UP (or expiry reverts to the sender).
                    delivery.setStatus(DeliveryStatus.AWAITING_HANDOFF);
                } else if (targetRoute.getStatus() == RouteStatus.VALIDATED || targetRoute.getStatus() == RouteStatus.IN_PROGRESS) {
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

        // ADR-029 parity for the unified transfer path. Seed a brand-new target route's home depot from
        // the moved deliveries so it won't later pull a spurious self-pickup, then reconcile PICKUP stops
        // on BOTH routes: the source may now hold orphaned pickups (their delivery left) and the target
        // may need new ones. Without this, transfer-stops left an orphaned source pickup that blocked
        // route close ("stops still active — #N PENDING"). The reconciler queries stops fresh, so it sees
        // the post-move state correctly.
        if (targetRoute.getDepotId() == null && firstMovedDepot != null) {
            targetRoute.setDepotId(firstMovedDepot);
        }
        pickupStopReconciler.reconcile(sourceRoute);
        pickupStopReconciler.reconcile(targetRoute);

        sourceRoute.setRouteVersion(sourceRoute.getRouteVersion() != null ? sourceRoute.getRouteVersion() + 1 : 2);
        routeRepository.save(sourceRoute);

        // Don't auto-close an emptied source route while its driver still physically holds parcels
        // pending a custody handoff (in-field transfer): closing here would freeze a closure report
        // and mark the tournée "done" while the colis is still in the sender's truck (phantom truth).
        // It stays IN_PROGRESS until the handoff confirms (custody leaves) or expires (stop reverts),
        // at which point HandoffService re-finalizes it. The manual-close path is guarded the same way.
        if (sourceRoute.getStops().isEmpty() && handoffDeliveryIds.isEmpty()) {
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

        // Open a formal custody handoff per in-field parcel — each side gets an
        // accurate, per-parcel real-time prompt (incoming / outgoing).
        for (UUID deliveryId : handoffDeliveryIds) {
            handoffService.request(deliveryId, sourceRoute.getDriverId(), targetRoute.getDriverId(), null, req.getReason());
        }

        // FCM: summary of the bulk move (handoffs are notified per-parcel above).
        eventPublisher.publishStopsTransferred(
                sourceRoute.getDriverId(),
                targetRoute.getDriverId(),
                transferredStops.size(),
                false);

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
