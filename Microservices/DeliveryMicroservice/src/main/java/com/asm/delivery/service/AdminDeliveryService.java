package com.asm.delivery.service;

import com.asm.delivery.dto.request.AssignDeliveryRequest;
import com.asm.delivery.dto.request.AdminExceptionReassignRequest;
import com.asm.delivery.dto.request.AdminExceptionReplanRequest;
import com.asm.delivery.dto.request.PinDropoffRequest;
import com.asm.delivery.dto.response.AdminDeliveryDetailResponse;
import com.asm.delivery.dto.response.AdminOpsAlertsResponse;
import com.asm.delivery.dto.response.AdminOpsAuditResponse;
import com.asm.delivery.dto.response.AdminOpsExceptionsResponse;
import com.asm.delivery.dto.response.AdminOpsLanesResponse;
import com.asm.delivery.dto.response.AdminOpsOverviewResponse;
import com.asm.delivery.dto.response.AdminDeliverySummaryResponse;
import com.asm.delivery.dto.response.AdminDriverResponse;
import com.asm.delivery.dto.response.AdminStatsResponse;
import com.asm.delivery.dto.response.GeocodeSuggestionResponse;
import com.asm.delivery.dto.response.StatusHistoryResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.DeliveryStatusHistory;
import com.asm.delivery.entity.FailureCode;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderSource;
import com.asm.delivery.entity.OrderStatus;
import com.asm.delivery.entity.Role;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.RouteStopStatus;
import com.asm.delivery.odoo.OdooSyncService;
import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.entity.Zone;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DeliveryStatusHistoryRepository;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.repository.ProofOfDeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.ZoneRepository;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.service.EventPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDeliveryService {

    @Value("${ops.sla.waiting-limit-minutes:15}")
    private int waitingLimitMinutes;

    @Value("${ops.sla.pickup-limit-minutes:20}")
    private int pickupLimitMinutes;

        @Value("${ops.sla.assign-limit-minutes:20}")
        private int assignLimitMinutes;

    @Value("${ops.sla.waiting-minutes:15}")
    private int waitingSlaMinutes;

    private static final List<DeliveryStatus> ACTIVE_STATUSES = List.of(
            DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.IN_TRANSIT
    );

    private static final List<DeliveryStatus> REASSIGN_ALLOWED_STATUSES = List.of(
            DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP
    );

    private static final List<DeliveryStatus> REPLAN_ALLOWED_STATUSES = List.of(
            DeliveryStatus.ASSIGNED,
            DeliveryStatus.PICKED_UP,
            DeliveryStatus.FAILED
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
    private final EventPublisher eventPublisher;
    private final OdooSyncService odooSyncService;
    private final ZoneRepository zoneRepository;
    private final GeocodingService geocodingService;
    private final AuditLogService auditLogService;
    private final SystemSettingsService systemSettingsService;
                private final DelayCalculationService delayCalculationService;
        private final RouteOptimizationService routeOptimizationService;

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
                        .available(d.isAvailable()
                                && !activeDeliveryMap.containsKey(d.getId())
                                && !activeRouteMap.containsKey(d.getId()))
                        .currentLat(d.getCurrentLat() != null ? BigDecimal.valueOf(d.getCurrentLat()) : null)
                        .currentLng(d.getCurrentLng() != null ? BigDecimal.valueOf(d.getCurrentLng()) : null)
                        .activeDeliveryId(activeDeliveryMap.get(d.getId()))
                        .activeRouteId(activeRouteMap.get(d.getId()))
                        .build())
                .toList();
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

        public AdminStatsResponse getStats(String period, LocalDate from, LocalDate to) {
                StatsRange range = resolveRange(period, from, to);

        return AdminStatsResponse.builder()
                                .today(buildTodayStats(range.start(), range.end(), range.period()))
                                .byDriver(buildDriverStats(range.start(), range.end()))
                                .byFailureCode(buildFailureStats(range.start(), range.end()))
                                .byCity(buildCityStats(range.start(), range.end()))
                                .byClient(buildClientStats(range.start(), range.end()))
                .build();
    }

        public AdminStatsResponse getStats() {
                return getStats("day", null, null);
        }

        public AdminOpsOverviewResponse getOpsOverview(String period,
                                                                                                   LocalDate from,
                                                                                                   LocalDate to,
                                                                                                   Integer waitingSlaOverride,
                                                                                                   Integer transitSlaOverride) {
                return buildOpsOverview(period, from, to, 4, 30, waitingSlaOverride, transitSlaOverride);
    }

        public AdminOpsOverviewResponse getOpsOverview(String period, LocalDate from, LocalDate to) {
                return getOpsOverview(period, from, to, null, null);
        }

        public AdminOpsLanesResponse getOpsLanes(String period,
                                                                                         LocalDate from,
                                                                                         LocalDate to,
                                                                                         Integer topItems,
                                                                                         Integer waitingSlaOverride,
                                                                                         Integer transitSlaOverride) {
        int top = topItems == null || topItems < 1 ? 4 : Math.min(topItems, 20);
                AdminOpsOverviewResponse overview = buildOpsOverview(period, from, to, top, 30, waitingSlaOverride, transitSlaOverride);
        return AdminOpsLanesResponse.builder()
                .generatedAt(overview.getGeneratedAt())
                .period(overview.getPeriod())
                .periodStart(overview.getPeriodStart())
                .periodEnd(overview.getPeriodEnd())
                .lanes(overview.getLanes())
                .build();
    }

        public AdminOpsLanesResponse getOpsLanes(String period, LocalDate from, LocalDate to, Integer topItems) {
                return getOpsLanes(period, from, to, topItems, null, null);
        }

        public AdminOpsAlertsResponse getOpsAlerts(String period,
                                                                                           LocalDate from,
                                                                                           LocalDate to,
                                                                                           Integer limit,
                                                                                           Integer waitingSlaOverride,
                                                                                           Integer transitSlaOverride) {
        int max = limit == null || limit < 1 ? 30 : Math.min(limit, 200);
                AdminOpsOverviewResponse overview = buildOpsOverview(period, from, to, 4, max, waitingSlaOverride, transitSlaOverride);
        return AdminOpsAlertsResponse.builder()
                .generatedAt(overview.getGeneratedAt())
                .period(overview.getPeriod())
                .periodStart(overview.getPeriodStart())
                .periodEnd(overview.getPeriodEnd())
                .sla(overview.getSla())
                .alerts(overview.getExceptions())
                .build();
    }

        public AdminOpsAlertsResponse getOpsAlerts(String period, LocalDate from, LocalDate to, Integer limit) {
                return getOpsAlerts(period, from, to, limit, null, null);
        }

        public AdminOpsAuditResponse getOpsAudit(String period,
                                                                                         LocalDate from,
                                                                                         LocalDate to,
                                                                                         Integer limit,
                                                                                         String actor,
                                                                                         String role,
                                                                                         DeliveryStatus status) {
                StatsRange range = resolveRange(period, from, to);
                int max = limit == null || limit < 1 ? 50 : Math.min(limit, 500);

                CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                CriteriaQuery<DeliveryStatusHistory> cq = cb.createQuery(DeliveryStatusHistory.class);
                Root<DeliveryStatusHistory> root = cq.from(DeliveryStatusHistory.class);

                List<Predicate> predicates = new ArrayList<>();
                predicates.add(cb.between(root.get("changedAt"), range.start(), range.end()));

                if (StringUtils.hasText(actor)) {
                        predicates.add(cb.like(cb.lower(root.get("changedBy")), "%" + actor.trim().toLowerCase(Locale.ROOT) + "%"));
                }

                if (StringUtils.hasText(role)) {
                        predicates.add(cb.equal(root.get("changedByRole"), parseRole(role)));
                }

                if (status != null) {
                        predicates.add(cb.equal(root.get("status"), status));
                }

                cq.select(root)
                                .where(predicates.toArray(Predicate[]::new))
                                .orderBy(cb.desc(root.get("changedAt")));

                List<DeliveryStatusHistory> historyRows = entityManager.createQuery(cq)
                                .setMaxResults(max)
                                .getResultList();

                CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
                Root<DeliveryStatusHistory> countRoot = countQuery.from(DeliveryStatusHistory.class);
                List<Predicate> countPredicates = new ArrayList<>();
                countPredicates.add(cb.between(countRoot.get("changedAt"), range.start(), range.end()));
                if (StringUtils.hasText(actor)) {
                        countPredicates.add(cb.like(cb.lower(countRoot.get("changedBy")), "%" + actor.trim().toLowerCase(Locale.ROOT) + "%"));
                }
                if (StringUtils.hasText(role)) {
                        countPredicates.add(cb.equal(countRoot.get("changedByRole"), parseRole(role)));
                }
                if (status != null) {
                        countPredicates.add(cb.equal(countRoot.get("status"), status));
                }

                countQuery.select(cb.count(countRoot)).where(countPredicates.toArray(Predicate[]::new));
                long total = entityManager.createQuery(countQuery).getSingleResult();

                List<UUID> deliveryIds = historyRows.stream()
                                .map(DeliveryStatusHistory::getDeliveryId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList();

                Map<UUID, Delivery> deliveryMap = deliveryIds.isEmpty()
                                ? Map.of()
                                : deliveryRepo.findAllByIdInWithOrder(deliveryIds).stream()
                                .collect(Collectors.toMap(Delivery::getId, d -> d));

                List<AdminOpsAuditResponse.AuditEvent> events = historyRows.stream()
                                .map(h -> {
                                        Delivery delivery = deliveryMap.get(h.getDeliveryId());
                                        Order order = delivery != null ? delivery.getOrder() : null;
                                        return AdminOpsAuditResponse.AuditEvent.builder()
                                                        .historyId(h.getId())
                                                        .deliveryId(h.getDeliveryId())
                                                        .orderId(order != null ? order.getId() : null)
                                                        .status(h.getStatus())
                                                        .changedBy(h.getChangedBy())
                                                        .changedByRole(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                                                        .note(h.getNote())
                                                        .clientName(order != null ? order.getClientName() : null)
                                                        .city(order != null ? order.getDropoffCity() : null)
                                                        .changedAt(h.getChangedAt())
                                                        .build();
                                })
                                .toList();

                return AdminOpsAuditResponse.builder()
                                .generatedAt(LocalDateTime.now())
                                .period(range.period())
                                .periodStart(range.start())
                                .periodEnd(range.end())
                                .total(total)
                                .events(events)
                                .build();
        }

        public AdminOpsExceptionsResponse getOpsExceptions(String period,
                                                                                                           LocalDate from,
                                                                                                           LocalDate to,
                                                                                                           Integer limit,
                                                                                                           String motif,
                                                                                                           UUID driverId,
                                                                                                           String zone) {
                StatsRange range = resolveRange(period, from, to);
                int max = limit == null || limit < 1 ? 50 : Math.min(limit, 200);

                CriteriaBuilder cb = entityManager.getCriteriaBuilder();
                CriteriaQuery<Delivery> cq = cb.createQuery(Delivery.class);
                Root<Delivery> root = cq.from(Delivery.class);
                root.fetch("order", JoinType.LEFT);

                List<Predicate> predicates = new ArrayList<>();
                Predicate createdInRange = cb.between(root.get("createdAt"), range.start(), range.end());
                Predicate updatedInRange = cb.between(root.get("updatedAt"), range.start(), range.end());
                predicates.add(cb.or(createdInRange, updatedInRange));

                if (driverId != null) {
                        predicates.add(cb.equal(root.get("driverId"), driverId));
                }

                cq.select(root)
                                .distinct(true)
                                .where(predicates.toArray(Predicate[]::new))
                                .orderBy(cb.desc(root.get("updatedAt")), cb.desc(root.get("createdAt")));

                List<Delivery> deliveries = entityManager.createQuery(cq)
                                .setMaxResults(2000)
                                .getResultList();

                Map<String, DriverDTO> driverMap = loadDriverMap(deliveries);
                Map<UUID, RouteInfo> routeInfoByDeliveryId = loadRouteInfoMap(deliveries);
                Set<UUID> zoneIds = deliveries.stream()
                                .map(Delivery::getOrder)
                                .filter(Objects::nonNull)
                                .map(Order::getZoneId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet());
                Map<UUID, String> zoneNameById = zoneIds.isEmpty()
                                ? Map.of()
                                : zoneRepository.findAllById(zoneIds).stream()
                                .collect(Collectors.toMap(Zone::getId, Zone::getName));
                LocalDateTime now = LocalDateTime.now();
                String motifQuery = StringUtils.hasText(motif) ? motif.trim().toLowerCase(Locale.ROOT) : null;
                String zoneQuery = StringUtils.hasText(zone) ? zone.trim().toLowerCase(Locale.ROOT) : null;

                List<AdminOpsExceptionsResponse.ExceptionItem> filteredItems = deliveries.stream()
                                .map(delivery -> toExceptionItem(delivery, driverMap, routeInfoByDeliveryId, zoneNameById, now))
                                .filter(Objects::nonNull)
                                .filter(item -> {
                                        if (motifQuery == null) {
                                                return true;
                                        }
                                        return containsIgnoreCase(item.getMotif(), motifQuery)
                                                        || containsIgnoreCase(item.getComment(), motifQuery);
                                })
                                .filter(item -> zoneQuery == null || containsIgnoreCase(item.getZoneName(), zoneQuery))
                                .sorted((a, b) -> {
                                        int severityOrder = severityScore(b.getSeverity()) - severityScore(a.getSeverity());
                                        if (severityOrder != 0) return severityOrder;
                                        LocalDateTime aTime = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
                                        LocalDateTime bTime = b.getUpdatedAt() != null ? b.getUpdatedAt() : b.getCreatedAt();
                                        if (aTime == null) aTime = LocalDateTime.MIN;
                                        if (bTime == null) bTime = LocalDateTime.MIN;
                                        return bTime.compareTo(aTime);
                                })
                                .toList();

                List<AdminOpsExceptionsResponse.ExceptionItem> pagedItems = filteredItems.stream()
                                .limit(max)
                                .toList();

                return AdminOpsExceptionsResponse.builder()
                                .generatedAt(now)
                                .period(range.period())
                                .periodStart(range.start())
                                .periodEnd(range.end())
                                .total(filteredItems.size())
                                .items(pagedItems)
                                .build();
        }

        @Transactional
        public AdminOpsExceptionsResponse.ExceptionItem reassignException(UUID deliveryId,
                                                                                                                          AdminExceptionReassignRequest request,
                                                                                                                          UserPrincipal principal) {
                Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                                .orElseThrow(() -> AppException.notFound("Delivery not found"));

                assertReassignAllowed(delivery);

                // When a parcel is already picked up, reassignment implies a physical handover.
                // We require a note to keep custody changes explicit in the audit trail.
                if (delivery.getStatus() == DeliveryStatus.PICKED_UP && !StringUtils.hasText(request.getNote())) {
                        throw AppException.badRequest("A handover note is required to reassign a picked-up delivery");
                }

                if (request.getDriverId().equals(delivery.getDriverId())) {
                        throw AppException.badRequest("Delivery is already assigned to this driver");
                }

                DriverDTO targetDriver = transportPort.getDriver(request.getDriverId().toString());
                if (targetDriver == null) {
                        throw AppException.badRequest("Target driver not found");
                }

                DeliveryStatus previousStatus = delivery.getStatus();
                UUID previousDriverId = delivery.getDriverId();

                LocalDateTime now = LocalDateTime.now();
                delivery.setDriverId(request.getDriverId());
                delivery.setStatus(DeliveryStatus.ASSIGNED);
                delivery.setAssignedAt(now);
                delivery.setPickedUpAt(null);
                delivery.setInTransitAt(null);
                delivery.setCompletedAt(null);
                delivery.setWaitingSlaMinutes(delayCalculationService.calculateWaitingSlaMinutes(delivery));
                delivery.setAssignSlaMinutes(null);
                delivery.setPickupSlaMinutes(null);
                delivery.setCancelledAt(null);
                delivery.setCancelReason(null);
                delivery.setCancelledBy(null);
                delivery.setFailedAt(null);
                delivery.setFailureCode(null);
                delivery.setFailReason(null);

                deliveryRepo.save(delivery);

                ActorInfo actor = resolveActor(principal);
                auditLogService.logAction(principal, "REASSIGN_DELIVERY", delivery.getId().toString(), 
                    String.format("Delivery %s reassigned from %s to %s. Note: %s", 
                    delivery.getId(), previousDriverId, request.getDriverId(), request.getNote()));

                // Keep route plan consistent with ownership change: move stop to the new driver's route.
                Set<UUID> affectedRouteIds = moveStopToDriverRoute(delivery, request.getDriverId(), actor.name(), request.getStartTimeWindow(), request.getEndTimeWindow());

                // Recompute ETAs/geometries on both source and target routes after ownership change.
                for (UUID routeId : affectedRouteIds) {
                        if (routeId == null) continue;
                        routeOptimizationService.recalculate(routeId);
                }

                if (previousDriverId != null && !previousDriverId.equals(request.getDriverId())) {
                        transportPort.setAvailability(previousDriverId.toString(), true);
                }
                transportPort.setAvailability(request.getDriverId().toString(), false);

                appendHistory(delivery,
                                DeliveryStatus.ASSIGNED,
                                actor.name(),
                                actor.role(),
                                buildReassignOpsNote(previousStatus, request.getNote()));

                eventPublisher.publishDeliveryReassigned(delivery.getOrder(), delivery, previousDriverId, request.getDriverId());

                return mapActionResult(delivery, "WARNING", "REASSIGNED", "Delivery reassigned to a new driver");
        }

        @Transactional
        public AdminOpsExceptionsResponse.ExceptionItem replanException(UUID deliveryId,
                                                                                                                        AdminExceptionReplanRequest request,
                                                                                                                        UserPrincipal principal) {
                Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                                .orElseThrow(() -> AppException.notFound("Delivery not found"));

                assertReplanAllowed(delivery);

                DeliveryStatus previousStatus = delivery.getStatus();
                UUID previousDriverId = delivery.getDriverId();
                if (delivery.getDriverId() != null) {
                        transportPort.setAvailability(delivery.getDriverId().toString(), true);
                }

                delivery.setDriverId(null);
                delivery.setStatus(DeliveryStatus.WAITING_DRIVER);
                delivery.setAssignedAt(null);
                delivery.setPickedUpAt(null);
                delivery.setInTransitAt(null);
                delivery.setCompletedAt(null);
                delivery.setWaitingSlaMinutes(null);
                delivery.setAssignSlaMinutes(null);
                delivery.setPickupSlaMinutes(null);
                delivery.setCancelledAt(null);
                delivery.setCancelReason(null);
                delivery.setCancelledBy(null);
                delivery.setFailedAt(null);
                delivery.setFailureCode(null);
                delivery.setFailReason(null);

                deliveryRepo.save(delivery);

                ActorInfo actor = resolveActor(principal);
                auditLogService.logAction(principal, "REPLAN_DELIVERY", delivery.getId().toString(), 
                    "Delivery returned to dispatch pool (WAITING_DRIVER). Note: " + request.getNote());

                // Replan means pull the delivery out of the current execution route and return it to dispatch pool.
                removeStopFromCurrentRoute(delivery.getId());

                appendHistory(delivery,
                                DeliveryStatus.WAITING_DRIVER,
                                actor.name(),
                                actor.role(),
                                buildReplanOpsNote(previousStatus, request.getNote()));

                eventPublisher.publishDeliveryReplanned(delivery.getOrder(), delivery, previousDriverId);

                return mapActionResult(delivery, "WARNING", "REPLANNED", "Delivery sent back to waiting lane");
        }

    private AdminOpsOverviewResponse buildOpsOverview(String period,
                                                      LocalDate from,
                                                      LocalDate to,
                                                      int topItems,
                                                                                                          int alertLimit,
                                                                                                          Integer waitingSlaOverride,
                                                                                                          Integer transitSlaOverride) {
        StatsRange range = resolveRange(period, from, to);
                int effectiveWaitingSlaMinutes = normalizeSlaThreshold(waitingSlaOverride, waitingSlaMinutes, "waitingSlaMinutes");

        TypedQuery<Delivery> query = entityManager.createQuery(
                "SELECT d FROM Delivery d JOIN FETCH d.order o WHERE d.createdAt BETWEEN :start AND :end ORDER BY d.createdAt DESC",
                Delivery.class
        );
        query.setParameter("start", range.start());
        query.setParameter("end", range.end());
        query.setMaxResults(2000);

        List<Delivery> scopedDeliveries = query.getResultList();
        Map<String, DriverDTO> driverMap = loadDriverMap(scopedDeliveries);
                Map<UUID, RouteInfo> routeInfoByDeliveryId = loadRouteInfoMap(scopedDeliveries);

        List<AdminDeliverySummaryResponse> summaries = scopedDeliveries.stream()
                .map(d -> {
                    DriverDTO driver = d.getDriverId() != null ? driverMap.get(d.getDriverId().toString()) : null;
                                        return toSummaryResponse(d, driver, routeInfoByDeliveryId.get(d.getId()));
                })
                .toList();

        LocalDateTime now = LocalDateTime.now();

        long waitingBreaches = summaries.stream()
                .filter(s -> "WAITING_DRIVER".equals(s.getStatus()))
                .filter(s -> s.getCreatedAt() != null && Duration.between(s.getCreatedAt(), now).toMinutes() > effectiveWaitingSlaMinutes)
                .count();

        List<AdminOpsOverviewResponse.LaneSnapshot> lanes = List.of(
                buildLaneSnapshot(DeliveryStatus.WAITING_DRIVER, "Planned", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.ASSIGNED, "Assigned", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.PICKED_UP, "Picked Up", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.IN_TRANSIT, "In Transit", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.DELIVERED, "Delivered", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.PARTIALLY_DELIVERED, "Partial", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.FAILED, "Failed", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.CANCELLED, "Cancelled", summaries, topItems)
        );

        List<AdminOpsOverviewResponse.ExceptionRow> exceptions = summaries.stream()
                .map(s -> toExceptionRow(s, now, effectiveWaitingSlaMinutes, 0))
                .filter(Objects::nonNull)
                .sorted((a, b) -> {
                    int severityOrder = severityScore(b.getSeverity()) - severityScore(a.getSeverity());
                    if (severityOrder != 0) return severityOrder;
                    LocalDateTime aTime = a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.MIN;
                    LocalDateTime bTime = b.getCreatedAt() != null ? b.getCreatedAt() : LocalDateTime.MIN;
                    return bTime.compareTo(aTime);
                })
                .limit(alertLimit)
                .toList();

        return AdminOpsOverviewResponse.builder()
                .generatedAt(now)
                .period(range.period())
                .periodStart(range.start())
                .periodEnd(range.end())
                .sla(AdminOpsOverviewResponse.SlaSnapshot.builder()
                        .waitingThresholdMinutes(effectiveWaitingSlaMinutes)
                        .transitThresholdMinutes(0)
                        .waitingBreaches(waitingBreaches)
                        .transitBreaches(0)
                        .totalBreaches(waitingBreaches)
                        .build())
                .lanes(lanes)
                .exceptions(exceptions)
                .build();
    }

    // ── Assign ────────────────────────────────────────────────────────────────

    @Transactional
    public AdminDeliveryDetailResponse assignDelivery(UUID deliveryId, AssignDeliveryRequest request) {
        driverDeliveryService.accept(deliveryId, request.getDriverId());
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
        routeStopRepository.findByDeliveryId(deliveryId).ifPresent(stop -> {
            Route route = stop.getRoute();
            if (route != null && (route.getStatus() == RouteStatus.VALIDATED || route.getStatus() == RouteStatus.IN_PROGRESS)) {
                route.setIsOptimized(false);
                routeRepository.save(route);
            }
        });

        return getDeliveryDetail(deliveryId);
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Transactional
    public void cancelDelivery(UUID deliveryId, String reason) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        if (!List.of(DeliveryStatus.WAITING_DRIVER, DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP).contains(delivery.getStatus())) {
            throw AppException.badRequest("Cannot cancel delivery in status " + delivery.getStatus());
        }

        if (delivery.getDriverId() != null) {
            transportPort.setAvailability(delivery.getDriverId().toString(), true);
        }

        Order order = delivery.getOrder();

        // Clean up delivery history to avoid orphan records
        var history = historyRepo.findByDeliveryIdOrderByChangedAtAsc(deliveryId);
        historyRepo.deleteAll(history);

        // Delete the delivery entirely
        deliveryRepo.delete(delivery);

        // To truly "return to import state", we must delete the associated Order if it came from Odoo.
        // Otherwise, it gets stuck as PENDING locally but `alreadyImported` stays true in the dashboard.
        if (order != null && order.getSource() == OrderSource.ODOO) {
            orderRepo.delete(order);
        } else if (order != null) {
            order.setStatus(OrderStatus.PENDING);
            orderRepo.save(order);
        }
    }

    @Transactional
    public AdminDeliveryDetailResponse createBackorderDelivery(UUID deliveryId) {
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> AppException.notFound("Delivery not found"));

        Order order = delivery.getOrder();
        if (order == null) throw AppException.badRequest("No order attached to this delivery");

        if (order.getOdooBackorderId() == null) {
            throw AppException.badRequest("No Odoo Backorder ID registered for this order.");
        }

        // We clone the order to create a new delivery task
        // By appending "-B1" etc, we bypass unique constraint locally, while OdooClient still knows to look up the original sale.order
        String originalErpId = order.getErpOrderId();
        String newErpId = originalErpId != null ? originalErpId + "-B" + System.currentTimeMillis() : null;

        // Calculate remaining items
        List<com.asm.delivery.entity.OrderItem> remainingItems = new ArrayList<>();
        int newTotalQuantity = 0;
        BigDecimal newTotalWeightKg = BigDecimal.ZERO;

        if (order.getItems() != null) {
            for (com.asm.delivery.entity.OrderItem item : order.getItems()) {
                int planned = item.getQuantity() != null ? item.getQuantity() : 0;
                int done = item.getQuantityDone() != null ? item.getQuantityDone() : 0;
                int remaining = Math.max(planned - done, 0);

                if (remaining > 0) {
                    com.asm.delivery.entity.OrderItem clonedItem = new com.asm.delivery.entity.OrderItem();
                    clonedItem.setId(item.getId());
                    clonedItem.setSku(item.getSku());
                    clonedItem.setName(item.getName());
                    clonedItem.setQuantity(remaining);
                    clonedItem.setQuantityDone(0);
                                        clonedItem.setUnitWeightKg(item.getUnitWeightKg());
                                        clonedItem.setUnitPrice(item.getUnitPrice());
                    remainingItems.add(clonedItem);
                    newTotalQuantity += remaining;

                                        BigDecimal unitWeight = item.getUnitWeightKg() != null ? item.getUnitWeightKg() : BigDecimal.ZERO;
                                        newTotalWeightKg = newTotalWeightKg.add(unitWeight.multiply(BigDecimal.valueOf(remaining)));
                }
            }
        }

        if (remainingItems.isEmpty()) {
            throw AppException.badRequest("No remaining items to backorder");
        }

        Order backorder = Order.builder()
                .source(order.getSource())
                .schemaVersion(order.getSchemaVersion())
                .clientId(order.getClientId())
                .clientName(order.getClientName())
                .clientPhone(order.getClientPhone())
                .clientEmail(order.getClientEmail())
                .erpOrderId(newErpId)
                .erpClientId(order.getErpClientId())
                .erpExternalRef(order.getErpExternalRef())
                .originName(order.getOriginName())
                .originAddress(order.getOriginAddress())
                .originCity(order.getOriginCity())
                .originPostalCode(order.getOriginPostalCode())
                .originCountryCode(order.getOriginCountryCode())
                .originContactName(order.getOriginContactName())
                .originContactPhone(order.getOriginContactPhone())
                .originContactEmail(order.getOriginContactEmail())
                .dropoffAddress(order.getDropoffAddress())
                .dropoffCity(order.getDropoffCity())
                .dropoffPostalCode(order.getDropoffPostalCode())
                .dropoffCountryCode(order.getDropoffCountryCode())
                .dropoffLat(order.getDropoffLat())
                .dropoffLng(order.getDropoffLng())
                .deliveryInstructions(order.getDeliveryInstructions())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .priority(order.getPriority())
                .status(OrderStatus.PENDING)
                .items(remainingItems)
                .totalQuantity(newTotalQuantity)
                .totalWeightKg(newTotalWeightKg)
                .odooSyncStatus(null) // Unsynced because we just created it
                .build();
                
        // Save the new Order
        backorder = orderRepo.save(backorder);

        // Delete Odoo Backorder ID from the original order because we processed it
        order.setOdooBackorderId(null);
        orderRepo.save(order);

        // Automatically create a Delivery task for this backorder
        Delivery newDelivery = Delivery.builder()
                .order(backorder)
                .status(DeliveryStatus.WAITING_DRIVER)
                .createdAt(LocalDateTime.now())
                .build();
        deliveryRepo.save(newDelivery);

        appendHistory(newDelivery,
                DeliveryStatus.WAITING_DRIVER,
                "SYSTEM",
                Role.SYSTEM,
                "Backorder created from partial delivery #" + shortDeliveryId(delivery.getId()) + ".");

        appendHistory(delivery,
                delivery.getStatus(),
                "SYSTEM",
                Role.SYSTEM,
                "Backorder delivery #" + shortDeliveryId(newDelivery.getId()) + " created for remaining items.");

        return getDeliveryDetail(delivery.getId()); 
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

    private AdminDeliverySummaryResponse toSummaryResponse(Delivery delivery, DriverDTO driver, RouteInfo routeInfo) {
        Order order = delivery.getOrder();
        boolean isDropoffPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;
        Zone zone = (order != null && order.getZoneId() != null)
                ? zoneRepository.findById(order.getZoneId()).orElse(null)
                : null;
        return AdminDeliverySummaryResponse.builder()
                .deliveryId(delivery.getId())
                .orderId(order != null ? order.getId() : null)
                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                .status(delivery.getStatus().name())
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
                .driverId(delivery.getDriverId())
                .driverName(driver != null ? driver.getName() : null)
                .driverPhone(driver != null ? driver.getPhone() : null)
                .totalAmount(order != null ? order.getTotalAmount() : null)
                .totalWeightKg(order != null ? order.getTotalWeightKg() : null)
                .routeDistanceKm(delivery.getRouteDistanceKm())
                .routeDurationMinutes(delivery.getRouteDurationMinutes())
                .transitSlaMinutesComputed(delivery.getTransitSlaMinutesComputed())
                .routeEtaAt(delivery.getRouteEtaAt())
                .routeGeometry(delivery.getRouteGeometry())
                .routeProvider(delivery.getRouteProvider())
                .createdAt(delivery.getCreatedAt())
                .assignedAt(delivery.getAssignedAt())
                .inTransitAt(delivery.getInTransitAt())
                .completedAt(delivery.getCompletedAt())
                .failedAt(delivery.getFailedAt())
                .cancelledAt(delivery.getCancelledAt())
                .updatedAt(delivery.getUpdatedAt())
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

        private AdminOpsOverviewResponse.LaneSnapshot buildLaneSnapshot(DeliveryStatus status,
                                                                                                                                        String label,
                                                                                                                                        List<AdminDeliverySummaryResponse> summaries,
                                                                                                                                        int topItems) {
                List<AdminDeliverySummaryResponse> filtered = summaries.stream()
                                .filter(s -> status.name().equals(s.getStatus()))
                                .toList();

                List<AdminOpsOverviewResponse.LaneDelivery> items = filtered.stream()
                                .limit(topItems)
                                .map(s -> AdminOpsOverviewResponse.LaneDelivery.builder()
                                                .deliveryId(s.getDeliveryId())
                                                .orderId(s.getOrderId())
                                                .clientName(s.getClientName())
                                                .city(s.getDropoffCity())
                                                .driverName(s.getDriverName())
                                                .createdAt(s.getCreatedAt())
                                                .build())
                                .toList();

                return AdminOpsOverviewResponse.LaneSnapshot.builder()
                                .status(status)
                                .label(label)
                                .count(filtered.size())
                                .items(items)
                                .build();
        }

        private AdminOpsOverviewResponse.ExceptionRow toExceptionRow(AdminDeliverySummaryResponse s,
                                                                                                                          LocalDateTime now,
                                                                                                                          int effectiveWaitingSlaMinutes,
                                                                                                                          int effectiveTransitSlaMinutes) {
                if ("FAILED".equals(s.getStatus())) {
                        return buildExceptionRow(s, DeliveryStatus.FAILED, "CRITICAL", "Livraison en échec : Nécessite une intervention manuelle");
                }
                if ("CANCELLED".equals(s.getStatus())) {
                        return buildExceptionRow(s, DeliveryStatus.CANCELLED, "CRITICAL", "Livraison annulée par le système ou l'utilisateur");
                }
                if ("PARTIALLY_DELIVERED".equals(s.getStatus())) {
                        return buildExceptionRow(s, DeliveryStatus.PARTIALLY_DELIVERED, "WARNING", "Livraison partielle signalée");
                }
                if ("WAITING_DRIVER".equals(s.getStatus()) && s.getCreatedAt() != null) {
                        int waitingLimit = systemSettingsService.getInt("ops.sla.waiting-limit-minutes", waitingLimitMinutes);
                        long elapsed = Duration.between(s.getCreatedAt(), now).toMinutes();
                        if (elapsed > waitingLimit) {
                                return buildExceptionRow(s, DeliveryStatus.WAITING_DRIVER, "WARNING", 
                                    String.format("Aucun livreur n'a accepté cette commande depuis %d minutes", elapsed));
                        }
                }
                if ("ASSIGNED".equals(s.getStatus())) {
                        int assignLimit = systemSettingsService.getInt("ops.sla.assign-limit-minutes", assignLimitMinutes);
                        Long elapsed = resolveAssignSlaElapsedMinutes(s, now);
                        if (elapsed == null) {
                                return null;
                        }
                        if (elapsed > assignLimit) {
                            return buildExceptionRow(s, DeliveryStatus.ASSIGNED, "CRITICAL", 
                                "SLA assign dépassé : le livreur tarde à récupérer le colis au dépôt");
                        }
                }
                if ("IN_TRANSIT".equals(s.getStatus())) {
                        // Utilisation du créneau horaire fixe défini par le dispatcher (Window End)
                        Optional<RouteStop> stopOpt = routeStopRepository.findByDeliveryId(s.getDeliveryId());
                        if (stopOpt.isPresent() && stopOpt.get().getEndTimeWindow() != null) {
                            LocalDateTime deadline = LocalDateTime.of(now.toLocalDate(), stopOpt.get().getEndTimeWindow());
                            if (now.isAfter(deadline)) {
                                return buildExceptionRow(s, DeliveryStatus.IN_TRANSIT, "CRITICAL", "Retard critique : Fenêtre de livraison client dépassée");
                            }
                        }
                }
                return null;
        }

        private Long resolveAssignSlaElapsedMinutes(AdminDeliverySummaryResponse s, LocalDateTime now) {
                return routeStopRepository.findByDeliveryId(s.getDeliveryId())
                                .map(RouteStop::getRoute)
                                .map(route -> {
                                        if (route == null) {
                                                return null;
                                        }
                                        if (route.getStartedAt() != null) {
                                                return Duration.between(route.getStartedAt(), now).toMinutes();
                                        }
                                        if (route.getDepartureTime() != null) {
                                                return Duration.between(route.getDepartureTime(), now).toMinutes();
                                        }
                                        if (route.getDate() != null && route.getPlannedStartTime() != null) {
                                                return Duration.between(route.getDate().atTime(route.getPlannedStartTime()), now).toMinutes();
                                        }
                                        return null;
                                })
                                .orElseGet(() -> s.getAssignedAt() != null
                                                ? Duration.between(s.getAssignedAt(), now).toMinutes()
                                                : null);
        }

        private AdminOpsOverviewResponse.ExceptionRow buildExceptionRow(AdminDeliverySummaryResponse s,
                                                                                                                                        DeliveryStatus status,
                                                                                                                                        String severity,
                                                                                                                                        String message) {
                return AdminOpsOverviewResponse.ExceptionRow.builder()
                                .deliveryId(s.getDeliveryId())
                                .orderId(s.getOrderId())
                                .status(status)
                                .clientName(s.getClientName())
                                .city(s.getDropoffCity())
                                .driverName(s.getDriverName())
                                .severity(severity)
                                .message(message)
                                .createdAt(s.getCreatedAt())
                                .build();
        }

        private int severityScore(String severity) {
                if ("CRITICAL".equalsIgnoreCase(severity)) return 3;
                if ("WARNING".equalsIgnoreCase(severity)) return 2;
                return 1;
        }

        private AdminOpsExceptionsResponse.ExceptionItem toExceptionItem(Delivery delivery,
                                                                                                                                                 Map<String, DriverDTO> driverMap,
                                                                                                                                                 Map<UUID, RouteInfo> routeInfoByDeliveryId,
                                                                                                                                                 Map<UUID, String> zoneNameById,
                                                                                                                                                 LocalDateTime now) {
                ExceptionClassification classification = classifyException(delivery, now);
                if (classification == null) {
                        return null;
                }

                Order order = delivery.getOrder();
                DriverDTO driver = delivery.getDriverId() != null ? driverMap.get(delivery.getDriverId().toString()) : null;
                RouteInfo routeInfo = routeInfoByDeliveryId.get(delivery.getId());

                return AdminOpsExceptionsResponse.ExceptionItem.builder()
                                .deliveryId(delivery.getId())
                                .orderId(order != null ? order.getId() : null)
                                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                                .status(delivery.getStatus())
                                .failureCode(delivery.getFailureCode())
                                .motif(classification.motif())
                                .driverId(delivery.getDriverId())
                                .driverName(driver != null ? driver.getName() : null)
                                .clientName(order != null ? order.getClientName() : null)
                                .city(order != null ? order.getDropoffCity() : null)
                                .zoneName(order != null && order.getZoneId() != null ? zoneNameById.get(order.getZoneId()) : null)
                                .severity(classification.severity())
                                .comment(classification.comment())
                                .createdAt(delivery.getCreatedAt())
                                .updatedAt(delivery.getUpdatedAt())
                                .build();
        }

        private ExceptionClassification classifyException(Delivery delivery, LocalDateTime now) {
                DeliveryStatus status = delivery.getStatus();
                if (status == DeliveryStatus.FAILED) {
                        String motif = delivery.getFailureCode() != null ? delivery.getFailureCode().name() : "FAILED";
                        String comment = StringUtils.hasText(delivery.getFailReason()) ? delivery.getFailReason() : "Delivery failed and requires follow-up";
                        return new ExceptionClassification("CRITICAL", motif, comment);
                }
                if (status == DeliveryStatus.ASSIGNED) {
                        int effectiveAssignLimit = systemSettingsService.getInt("ops.sla.assign-limit-minutes", assignLimitMinutes);
                        LocalDateTime baseline = delivery.getAssignedAt() != null ? delivery.getAssignedAt() : delivery.getUpdatedAt();
                        if (baseline == null) {
                                baseline = delivery.getCreatedAt();
                        }

                        Optional<RouteStop> currentStopOpt = routeStopRepository.findByDeliveryId(delivery.getId());
                        if (currentStopOpt.isPresent()) {
                                RouteStop currentStop = currentStopOpt.get();
                                Route route = currentStop.getRoute();
                                Integer currentOrder = currentStop.getStopOrder();

                                if (route != null && currentOrder != null && currentOrder > 1) {
                                        RouteStop previousStop = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId()).stream()
                                                        .filter(stop -> stop.getStopOrder() != null && stop.getStopOrder() < currentOrder)
                                                        .max(Comparator.comparingInt(RouteStop::getStopOrder))
                                                        .orElse(null);

                                        if (previousStop != null && !isRouteStopFinished(previousStop.getStatus())) {
                                                return new ExceptionClassification(
                                                                "WARNING",
                                                                "ASSIGNED_MONITORING",
                                                                "Assigned delivery is queued behind a previous route stop"
                                                );
                                        }

                                        if (previousStop != null) {
                                                LocalDateTime readyAt = previousStop.getCompletedAt() != null
                                                                ? previousStop.getCompletedAt()
                                                                : previousStop.getUpdatedAt();
                                                if (readyAt != null) {
                                                        baseline = readyAt;
                                                }
                                        }
                                }
                        }

                        long elapsed = baseline != null ? Duration.between(baseline, now).toMinutes() : 0;
                        String motif = elapsed > effectiveAssignLimit ? "SLA_ASSIGN_DRIVER" : "ASSIGNED_MONITORING";
                        String comment = elapsed > effectiveAssignLimit
                                        ? "Assigned delivery has not been picked up within SLA"
                                        : "Assigned delivery available for dispatch monitoring";
                        return new ExceptionClassification("WARNING", motif, comment);
                }
                if (status == DeliveryStatus.CANCELLED) {
                        String comment = StringUtils.hasText(delivery.getCancelReason()) ? delivery.getCancelReason() : "Delivery cancelled and requires review";
                        return new ExceptionClassification("CRITICAL", "CANCELLED", comment);
                }
                if (status == DeliveryStatus.PARTIALLY_DELIVERED) {
                        return new ExceptionClassification("WARNING", "PARTIAL_DELIVERY", "Partial delivery reported");
                }
                if (status == DeliveryStatus.WAITING_DRIVER && delivery.getCreatedAt() != null) {
                        long elapsed = Duration.between(delivery.getCreatedAt(), now).toMinutes();
                        if (elapsed > waitingSlaMinutes) {
                                return new ExceptionClassification("WARNING", "SLA_WAITING_DRIVER", "Waiting driver SLA breached");
                        }
                }
                if (status == DeliveryStatus.IN_TRANSIT) {
                        Optional<RouteStop> stopOpt = routeStopRepository.findByDeliveryId(delivery.getId());
                        if (stopOpt.isPresent() && stopOpt.get().getEndTimeWindow() != null) {
                                Route route = stopOpt.get().getRoute();
                                if (route != null && route.getDate() != null) {
                                        LocalDateTime deadline = route.getDate().atTime(stopOpt.get().getEndTimeWindow());
                                        if (now.isAfter(deadline)) {
                                                return new ExceptionClassification("CRITICAL", "SLA_IN_TRANSIT", "Delivery time window exceeded");
                                        }
                                }
                        }
                }
                return null;
        }

        private boolean isRouteStopFinished(RouteStopStatus status) {
                return status == RouteStopStatus.COMPLETED
                                || status == RouteStopStatus.FAILED
                                || status == RouteStopStatus.PARTIAL;
        }

        private AdminOpsExceptionsResponse.ExceptionItem mapActionResult(Delivery delivery,
                                                                                                                                                  String severity,
                                                                                                                                                  String motif,
                                                                                                                                                  String comment) {
                Order order = delivery.getOrder();
                DriverDTO driver = delivery.getDriverId() != null ? transportPort.getDriver(delivery.getDriverId().toString()) : null;
                RouteInfo routeInfo = loadRouteInfoMap(List.of(delivery)).get(delivery.getId());
                String zoneName = null;
                if (order != null && order.getZoneId() != null) {
                        zoneName = zoneRepository.findById(order.getZoneId()).map(Zone::getName).orElse(null);
                }
                return AdminOpsExceptionsResponse.ExceptionItem.builder()
                                .deliveryId(delivery.getId())
                                .orderId(order != null ? order.getId() : null)
                                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                                .status(delivery.getStatus())
                                .failureCode(delivery.getFailureCode())
                                .motif(motif)
                                .driverId(delivery.getDriverId())
                                .driverName(driver != null ? driver.getName() : null)
                                .clientName(order != null ? order.getClientName() : null)
                                .city(order != null ? order.getDropoffCity() : null)
                                .zoneName(zoneName)
                                .severity(severity)
                                .comment(comment)
                                .createdAt(delivery.getCreatedAt())
                                .updatedAt(delivery.getUpdatedAt())
                                .build();
        }

        private boolean containsIgnoreCase(String value, String query) {
                return value != null && value.toLowerCase(Locale.ROOT).contains(query);
        }

        private void appendHistory(Delivery delivery, DeliveryStatus status, String changedBy, Role role, String note) {
                historyRepo.save(DeliveryStatusHistory.builder()
                                .deliveryId(delivery.getId())
                                .status(status)
                                .changedBy(changedBy)
                                .changedByRole(role)
                                .note(note)
                                .build());
        }

        private ActorInfo resolveActor(UserPrincipal principal) {
                if (principal == null) {
                        return new ActorInfo("SYSTEM", Role.SYSTEM);
                }

                String actorName = StringUtils.hasText(principal.getName())
                                ? principal.getName().trim()
                                : (StringUtils.hasText(principal.getUserId()) ? principal.getUserId().trim() : "SYSTEM");

                Role role;
                try {
                        role = StringUtils.hasText(principal.getRole())
                                        ? Role.valueOf(principal.getRole().trim().toUpperCase(Locale.ROOT))
                                        : Role.ADMIN;
                } catch (IllegalArgumentException ex) {
                        role = Role.ADMIN;
                }
                return new ActorInfo(actorName, role);
        }

        private String buildReassignOpsNote(DeliveryStatus previousStatus, String note) {
                String message;
                if (previousStatus == DeliveryStatus.PICKED_UP) {
                        message = "Picked-up delivery reassigned with handover confirmation.";
                } else {
                        message = "Delivery reassigned to another driver by dispatch.";
                }
                return appendReason(message, note);
        }

        private String buildReplanOpsNote(DeliveryStatus previousStatus, String note) {
                String message;
                if (previousStatus == DeliveryStatus.FAILED) {
                        message = "Delivery failed earlier today. Replanned for a new attempt.";
                } else if (previousStatus == DeliveryStatus.PARTIALLY_DELIVERED) {
                        message = "Delivery was partially delivered today. Remaining items moved to replanning queue.";
                } else {
                        message = "Delivery moved back to planning queue by dispatch.";
                }
                return appendReason(message, note);
        }

        private String appendReason(String message, String note) {
                if (!StringUtils.hasText(note)) {
                        return message;
                }
                return message + " Reason: " + note.trim();
        }

        private String shortDeliveryId(UUID deliveryId) {
                if (deliveryId == null) {
                        return "UNKNOWN";
                }
                String raw = deliveryId.toString();
                return raw.length() <= 8 ? raw : raw.substring(0, 8);
        }

        private void assertReassignAllowed(Delivery delivery) {
                if (!REASSIGN_ALLOWED_STATUSES.contains(delivery.getStatus())) {
                        throw AppException.badRequest("Reassign is allowed only for ASSIGNED or PICKED_UP deliveries");
                }
        }

        private void assertReplanAllowed(Delivery delivery) {
                if (!REPLAN_ALLOWED_STATUSES.contains(delivery.getStatus())) {
                        throw AppException.badRequest("Replan is allowed only for ASSIGNED, PICKED_UP, or FAILED deliveries");
                }
        }

        private void removeStopFromCurrentRoute(UUID deliveryId) {
                routeStopRepository.findByDeliveryId(deliveryId).ifPresent(stop -> {
                        UUID previousRouteId = stop.getRoute().getId();
                        routeStopRepository.delete(stop);
                        repackStopOrder(previousRouteId);
                });
        }

        private Set<UUID> moveStopToDriverRoute(Delivery delivery, UUID targetDriverId, String actorName, java.time.LocalTime requestedStartTime, java.time.LocalTime requestedEndTime) {
                Set<UUID> affectedRouteIds = new LinkedHashSet<>();
                routeStopRepository.findByDeliveryId(delivery.getId()).ifPresent(currentStop -> {
                        Route sourceRoute = currentStop.getRoute();
                        if (sourceRoute == null) {
                                return;
                        }

                        if (targetDriverId.equals(sourceRoute.getDriverId())) {
                                return;
                        }

                        UUID sourceRouteId = sourceRoute.getId();
                        affectedRouteIds.add(sourceRouteId);
                        routeStopRepository.delete(currentStop);
                        repackStopOrder(sourceRouteId);

                        Route targetRoute = findOrCreateRouteForDriver(targetDriverId, sourceRoute, actorName);
                        affectedRouteIds.add(targetRoute.getId());
                        List<RouteStop> targetStops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(targetRoute.getId());
                        
                        // User-requested boundary validation
                        if (requestedStartTime != null && !targetStops.isEmpty()) {
                                RouteStop lastTargetStop = targetStops.get(targetStops.size() - 1);
                                java.time.LocalTime referenceTime = lastTargetStop.getEndTimeWindow() != null 
                                            ? lastTargetStop.getEndTimeWindow() 
                                            : (lastTargetStop.getEtaAt() != null ? lastTargetStop.getEtaAt().toLocalTime() : targetRoute.getPlannedStartTime());
                                
                                if (referenceTime != null && requestedStartTime.isBefore(referenceTime)) {
                                        throw AppException.badRequest("Reassigned stop start time (" + requestedStartTime + ") must be after current route's last stop time (" + referenceTime + ")");
                                }
                        }

                        // Determine final windows
                        java.time.LocalTime finalStartTime = requestedStartTime != null ? requestedStartTime : currentStop.getStartTimeWindow();
                        java.time.LocalTime finalEndTime = requestedEndTime != null ? requestedEndTime : currentStop.getEndTimeWindow();

                        // Extend Route boundaries if the newly placed window is outside Current bounds
                        boolean routeModified = false;
                        if (finalStartTime != null && finalStartTime.isBefore(targetRoute.getPlannedStartTime())) {
                                targetRoute.setPlannedStartTime(finalStartTime);
                                routeModified = true;
                        }
                        if (finalEndTime != null && finalEndTime.isAfter(targetRoute.getPlannedEndTime())) {
                                targetRoute.setPlannedEndTime(finalEndTime);
                                routeModified = true;
                        }
                        if (routeModified) {
                                routeRepository.save(targetRoute);
                        }

                        int nextOrder = targetStops.size() + 1;

                        routeStopRepository.save(RouteStop.builder()
                                        .route(targetRoute)
                                        .deliveryId(delivery.getId())
                                        .stopOrder(nextOrder)
                                        .status(RouteStopStatus.PENDING)
                                        .notes("Moved by dispatch via reassign")
                                        .startTimeWindow(finalStartTime)
                                        .endTimeWindow(finalEndTime)
                                        .bufferMinutes(currentStop.getBufferMinutes())
                                        .build());
                });
                return affectedRouteIds;
        }

        private Route findOrCreateRouteForDriver(UUID driverId, Route sourceRoute, String actorName) {
                List<RouteStatus> activeStatuses = List.of(RouteStatus.DRAFT, RouteStatus.VALIDATED, RouteStatus.IN_PROGRESS);
                List<Route> routes = routeRepository.findByDriverIdAndDateAndStatusIn(driverId, sourceRoute.getDate(), activeStatuses);
                if (!routes.isEmpty()) {
                        return routes.get(0);
                }

                String creator = StringUtils.hasText(actorName) ? actorName : "SYSTEM";
                Route draftRoute = Route.builder()
                                .name("Dispatch route " + sourceRoute.getDate() + " " + driverId.toString().substring(0, 8))
                                .driverId(driverId)
                                .vehicleId(null)
                                .date(sourceRoute.getDate())
                                .plannedStartTime(sourceRoute.getPlannedStartTime() != null ? sourceRoute.getPlannedStartTime() : LocalTime.of(8, 0))
                                .plannedEndTime(sourceRoute.getPlannedEndTime() != null ? sourceRoute.getPlannedEndTime() : LocalTime.of(18, 0))
                                .depotId(sourceRoute.getDepotId())
                                .departureTime(sourceRoute.getDepartureTime())
                                .city(sourceRoute.getCity())
                                .status(RouteStatus.DRAFT)
                                .createdBy(creator)
                                .build();
                return routeRepository.save(draftRoute);
        }

        private void repackStopOrder(UUID routeId) {
                List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(routeId);
                for (int i = 0; i < stops.size(); i++) {
                        stops.get(i).setStopOrder(i + 1);
                }
                routeStopRepository.saveAll(stops);
        }

        private record ExceptionClassification(String severity, String motif, String comment) {}

        private record ActorInfo(String name, Role role) {}

    private AdminDeliveryDetailResponse toDetailResponse(Delivery delivery,
                                                         DriverDTO driver,
                                                         List<StatusHistoryResponse> history,
                                                         boolean podExists) {
        Order order = delivery.getOrder();
        boolean isDropoffPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;
        Zone zone = (order != null && order.getZoneId() != null)
                ? zoneRepository.findById(order.getZoneId()).orElse(null)
                : null;
        return AdminDeliveryDetailResponse.builder()
                .deliveryId(delivery.getId())
                .orderId(order != null ? order.getId() : null)
                .status(delivery.getStatus().name())
                .failureCode(delivery.getFailureCode())
                .failureComment(delivery.getFailReason())
                .driverId(delivery.getDriverId())
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
                .items(order != null ? order.getItems() : null)
                .totalAmount(order != null ? order.getTotalAmount() : null)
                .totalWeightKg(order != null ? order.getTotalWeightKg() : null)
                .routeDistanceKm(delivery.getRouteDistanceKm())
                .routeDurationMinutes(delivery.getRouteDurationMinutes())
                .transitSlaMinutesComputed(delivery.getTransitSlaMinutesComputed())
                .routeEtaAt(delivery.getRouteEtaAt())
                .routeGeometry(delivery.getRouteGeometry())
                .routeProvider(delivery.getRouteProvider())
                .currency(order != null ? order.getCurrency() : null)
                .odooSyncStatus(order != null ? order.getOdooSyncStatus() : null)
                .odooBackorderId(order != null ? order.getOdooBackorderId() : null)
                .createdAt(delivery.getCreatedAt())
                .assignedAt(delivery.getAssignedAt())
                .pickedUpAt(delivery.getPickedUpAt())
                .inTransitAt(delivery.getInTransitAt())
                .completedAt(delivery.getCompletedAt())
                .failedAt(delivery.getFailedAt())
                .cancelledAt(delivery.getCancelledAt())
                .podExists(podExists)
                .statusHistory(history)
                .build();
    }

    private StatusHistoryResponse toHistoryResponse(DeliveryStatusHistory h) {
        return StatusHistoryResponse.builder()
                                .id(h.getId() != null ? h.getId().toString() : null)
                .status(h.getStatus().name())
                                .actor(h.getChangedBy())
                                .timestamp(h.getChangedAt())
                .changedBy(h.getChangedBy())
                .changedByRole(h.getChangedByRole() != null ? h.getChangedByRole().name() : null)
                .note(h.getNote())
                .changedAt(h.getChangedAt())
                .build();
    }

        private AdminStatsResponse.TodayStats buildTodayStats(LocalDateTime start, LocalDateTime end, String period) {
        long total     = countByCreatedAt(start, end);
        long delivered = countByField("completedAt", start, end);
        long failed    = countByField("failedAt", start, end);
        long inTransit = countStatusWithin("inTransitAt", DeliveryStatus.IN_TRANSIT, start, end);
        long waiting   = countStatusWithin("createdAt", DeliveryStatus.WAITING_DRIVER, start, end);
        long assigned  = countStatusWithin("assignedAt", DeliveryStatus.ASSIGNED, start, end);
        double successRate = total > 0 ? ((double) delivered / total) * 100.0 : 0.0;

        return AdminStatsResponse.TodayStats.builder()
                .total(total).delivered(delivered).failed(failed)
                .inTransit(inTransit).waiting(waiting).assigned(assigned)
                .successRate(round2(successRate))
                .avgAssignToPickupMinutes(averageDurationMinutes("assignedAt", "pickedUpAt", start, end))
                .avgPickupToTransitMinutes(averageDurationMinutes("pickedUpAt", "inTransitAt", start, end))
                .avgTransitToCompletionMinutes(averageDurationMinutes("inTransitAt", "completedAt", start, end))
                                .period(period)
                                .periodStart(start)
                                .periodEnd(end)
                .build();
    }

        private StatsRange resolveRange(String period, LocalDate from, LocalDate to) {
                LocalDateTime now = LocalDateTime.now();
                String normalized = period == null ? "day" : period.trim().toLowerCase(Locale.ROOT);
                return switch (normalized) {
                        case "week" -> {
                                LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
                                yield new StatsRange("week", monday.atStartOfDay(), now);
                        }
                        case "month" -> {
                                LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
                                yield new StatsRange("month", firstOfMonth.atStartOfDay(), now);
                        }
                        case "custom" -> {
                                if (from == null || to == null) {
                                        throw AppException.badRequest("For custom period, both 'from' and 'to' are required");
                                }
                                if (to.isBefore(from)) {
                                        throw AppException.badRequest("'to' must be greater than or equal to 'from'");
                                }
                                LocalDateTime start = from.atStartOfDay();
                                LocalDateTime end = to.atTime(LocalTime.MAX);
                                yield new StatsRange("custom", start, end);
                        }
                        default -> new StatsRange("day", LocalDate.now().atStartOfDay(), now);
                };
        }

        private record StatsRange(String period, LocalDateTime start, LocalDateTime end) {}

    private long countByCreatedAt(LocalDateTime start, LocalDateTime end) {
        return entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE d.createdAt BETWEEN :start AND :end", Long.class)
                .setParameter("start", start).setParameter("end", end).getSingleResult();
    }

    private long countByField(String fieldName, LocalDateTime start, LocalDateTime end) {
        return entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE d." + fieldName + " BETWEEN :start AND :end", Long.class)
                .setParameter("start", start).setParameter("end", end).getSingleResult();
    }

    private long countStatusWithin(String timestampField, DeliveryStatus status, LocalDateTime start, LocalDateTime end) {
        return entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE d.status = :status AND d." + timestampField + " BETWEEN :start AND :end",
                Long.class)
                .setParameter("status", status).setParameter("start", start).setParameter("end", end)
                .getSingleResult();
    }

    private List<AdminStatsResponse.DriverStats> buildDriverStats(LocalDateTime start, LocalDateTime end) {
        TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT d.driverId, COUNT(d), " +
                        "SUM(CASE WHEN d.status = :delivered THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN d.status = :failed THEN 1 ELSE 0 END) " +
                        "FROM Delivery d WHERE d.driverId IS NOT NULL AND d.createdAt BETWEEN :start AND :end " +
                        "GROUP BY d.driverId",
                Object[].class);
        query.setParameter("delivered", DeliveryStatus.DELIVERED);
        query.setParameter("failed", DeliveryStatus.FAILED);
        query.setParameter("start", start);
        query.setParameter("end", end);

        return query.getResultList().stream()
                .map(row -> {
                    UUID driverId = (UUID) row[0];
                    long total    = row[1] != null ? ((Number) row[1]).longValue() : 0;
                    long del      = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    long fail     = row[3] != null ? ((Number) row[3]).longValue() : 0;
                    double sr     = total > 0 ? ((double) del / total) * 100.0 : 0.0;

                    String driverName = null;
                    if (driverId != null) {
                        DriverDTO dto = transportPort.getDriver(driverId.toString());
                        if (dto != null) driverName = dto.getName();
                    }

                    return AdminStatsResponse.DriverStats.builder()
                            .driverId(driverId != null ? driverId.toString() : null)
                            .driverName(driverName)
                            .total(total).delivered(del).failed(fail).successRate(round2(sr))
                            .build();
                })
                .toList();
    }

    private List<AdminStatsResponse.FailureStats> buildFailureStats(LocalDateTime start, LocalDateTime end) {
        TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT d.failureCode, COUNT(d) FROM Delivery d " +
                        "WHERE d.failureCode IS NOT NULL AND d.failedAt BETWEEN :start AND :end " +
                        "GROUP BY d.failureCode",
                Object[].class);
        query.setParameter("start", start).setParameter("end", end);

        return query.getResultList().stream()
                .map(row -> AdminStatsResponse.FailureStats.builder()
                        .code(row[0] != null ? row[0].toString() : null)
                        .count(row[1] != null ? ((Number) row[1]).longValue() : 0)
                        .build())
                .toList();
    }

    private List<AdminStatsResponse.CityStats> buildCityStats(LocalDateTime start, LocalDateTime end) {
        TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT COALESCE(o.dropoffCity, 'Unknown'), COUNT(d), " +
                        "SUM(CASE WHEN d.status = :delivered THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN d.status = :failed THEN 1 ELSE 0 END) " +
                        "FROM Delivery d JOIN d.order o " +
                        "WHERE d.createdAt BETWEEN :start AND :end " +
                        "GROUP BY o.dropoffCity ORDER BY COUNT(d) DESC",
                Object[].class);
        query.setParameter("delivered", DeliveryStatus.DELIVERED);
        query.setParameter("failed", DeliveryStatus.FAILED);
        query.setParameter("start", start);
        query.setParameter("end", end);

        return query.getResultList().stream()
                .map(row -> {
                    String city = row[0] != null ? row[0].toString() : "Unknown";
                    long total = row[1] != null ? ((Number) row[1]).longValue() : 0;
                    long delivered = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    long failed = row[3] != null ? ((Number) row[3]).longValue() : 0;
                    double sr = total > 0 ? ((double) delivered / total) * 100.0 : 0.0;
                    return AdminStatsResponse.CityStats.builder()
                            .city(city)
                            .total(total)
                            .delivered(delivered)
                            .failed(failed)
                            .successRate(round2(sr))
                            .build();
                })
                .limit(8)
                .toList();
    }

    private List<AdminStatsResponse.ClientStats> buildClientStats(LocalDateTime start, LocalDateTime end) {
        TypedQuery<Object[]> query = entityManager.createQuery(
                "SELECT COALESCE(o.clientName, 'Unknown'), COUNT(d), " +
                        "SUM(CASE WHEN d.status = :delivered THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN d.status = :failed THEN 1 ELSE 0 END) " +
                        "FROM Delivery d JOIN d.order o " +
                        "WHERE d.createdAt BETWEEN :start AND :end " +
                        "GROUP BY o.clientName ORDER BY COUNT(d) DESC",
                Object[].class);
        query.setParameter("delivered", DeliveryStatus.DELIVERED);
        query.setParameter("failed", DeliveryStatus.FAILED);
        query.setParameter("start", start);
        query.setParameter("end", end);

        return query.getResultList().stream()
                .map(row -> {
                    String clientName = row[0] != null ? row[0].toString() : "Unknown";
                    long total = row[1] != null ? ((Number) row[1]).longValue() : 0;
                    long delivered = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    long failed = row[3] != null ? ((Number) row[3]).longValue() : 0;
                    double sr = total > 0 ? ((double) delivered / total) * 100.0 : 0.0;
                    return AdminStatsResponse.ClientStats.builder()
                            .clientName(clientName)
                            .total(total)
                            .delivered(delivered)
                            .failed(failed)
                            .successRate(round2(sr))
                            .build();
                })
                .limit(8)
                .toList();
    }

    private double averageDurationMinutes(String startField, String endField, LocalDateTime start, LocalDateTime end) {
        @SuppressWarnings("unchecked")
        List<Object[]> pairs = entityManager.createQuery(
                        "SELECT d." + startField + ", d." + endField + " FROM Delivery d " +
                                "WHERE d." + startField + " IS NOT NULL AND d." + endField + " IS NOT NULL " +
                                "AND d.createdAt BETWEEN :start AND :end")
                .setParameter("start", start)
                .setParameter("end", end)
                .getResultList();

        if (pairs.isEmpty()) return 0.0;

        long totalMinutes = 0L;
        long count = 0L;
        for (Object[] pair : pairs) {
            if (pair == null || pair.length < 2) continue;
            LocalDateTime from = (LocalDateTime) pair[0];
            LocalDateTime to = (LocalDateTime) pair[1];
            if (from == null || to == null || to.isBefore(from)) continue;
            totalMinutes += Duration.between(from, to).toMinutes();
            count += 1;
        }
        if (count == 0) return 0.0;
        return round2((double) totalMinutes / count);
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

        private int normalizeSlaThreshold(Integer overrideValue, int defaultValue, String fieldName) {
                if (overrideValue == null) return defaultValue;
                if (overrideValue < 1 || overrideValue > 1440) {
                        throw AppException.badRequest(fieldName + " must be between 1 and 1440 minutes");
                }
                return overrideValue;
        }

        private Role parseRole(String role) {
                try {
                        return Role.valueOf(role.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                        throw AppException.badRequest("Invalid role filter: " + role);
                }
        }

    private static UUID parseUuid(String id) {
        if (id == null) return null;
        try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
    }
}
