package com.asm.delivery.service.analytics;

import com.asm.delivery.entity.Order;

import com.asm.delivery.exception.AppException;

import com.asm.delivery.dto.response.*;
import com.asm.delivery.entity.*;
import com.asm.delivery.repository.*;
import com.asm.delivery.security.UserPrincipal;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import com.asm.delivery.service.DelayCalculationService;
import com.asm.delivery.service.SystemSettingsService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
public class OpsAnalyticsService {

    @Value("${ops.sla.waiting-limit-minutes:15}")
    private int waitingLimitMinutes;

    @Value("${ops.sla.assign-limit-minutes:20}")
    private int assignLimitMinutes;

    @Value("${ops.sla.waiting-minutes:15}")
    private int waitingSlaMinutes;

    private final EntityManager entityManager;
    private final DeliveryRepository deliveryRepo;
    private final RouteStopRepository routeStopRepository;
    private final SystemSettingsService systemSettingsService;
    private final TransportPort transportPort;
    private final ZoneRepository zoneRepository;
    private final DeliveryStatusHistoryRepository historyRepo;
    private final DelayCalculationService delayCalculationService;
    private final com.asm.delivery.sla.SlaStateRepository slaStateRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ExceptionClassifier exceptionClassifier;

        @Transactional(readOnly = true)
        public AdminStatsResponse getStats(String period, LocalDate from, LocalDate to) {
        StatsRange range = resolveRange(period, from, to);
        AdminStatsResponse response = doBuildStats(range);
        enrichDriverNames(response);
        return response;
    }

    @Transactional(readOnly = true)
    public AdminStatsResponse doBuildStats(StatsRange range) {
        return AdminStatsResponse.builder()
                .today(buildTodayStats(range.start(), range.end(), range.period()))
                .byDriver(buildDriverStats(range.start(), range.end()))
                .byFailureCode(buildFailureStats(range.start(), range.end()))
                .byCity(buildCityStats(range.start(), range.end()))
                .byClient(buildClientStats(range.start(), range.end()))
                .topItems(buildTopItems(range.start(), range.end(), 10))
                .build();
    }

        @Transactional(readOnly = true)
        public AdminStatsResponse getStats() {
                return getStats("day", null, null);
        }

        @Transactional(readOnly = true)
        public AdminOpsOverviewResponse getOpsOverview(String period,
                                                                                                   LocalDate from,
                                                                                                   LocalDate to,
                                                                                                   Integer waitingSlaOverride,
                                                                                                   Integer transitSlaOverride) {
                return buildOpsOverview(period, from, to, 200, 100, waitingSlaOverride, transitSlaOverride);
    }

        @Transactional(readOnly = true)
        public AdminOpsOverviewResponse getOpsOverview(String period, LocalDate from, LocalDate to) {
                return getOpsOverview(period, from, to, null, null);
        }

        @Transactional(readOnly = true)
        public AdminOpsLanesResponse getOpsLanes(String period,
                                                                                         LocalDate from,
                                                                                         LocalDate to,
                                                                                         Integer topItems,
                                                                                         Integer waitingSlaOverride,
                                                                                         Integer transitSlaOverride) {
        int top = topItems == null || topItems < 1 ? 100 : Math.min(topItems, 500);
                AdminOpsOverviewResponse overview = buildOpsOverview(period, from, to, top, 100, waitingSlaOverride, transitSlaOverride);
        return AdminOpsLanesResponse.builder()
                .generatedAt(overview.getGeneratedAt())
                .period(overview.getPeriod())
                .periodStart(overview.getPeriodStart())
                .periodEnd(overview.getPeriodEnd())
                .lanes(overview.getLanes())
                .build();
    }

        @Transactional(readOnly = true)
        public AdminOpsLanesResponse getOpsLanes(String period, LocalDate from, LocalDate to, Integer topItems) {
                return getOpsLanes(period, from, to, topItems, null, null);
        }

        @Transactional(readOnly = true)
        public AdminOpsAlertsResponse getOpsAlerts(String period,
                                                                                           LocalDate from,
                                                                                           LocalDate to,
                                                                                           Integer limit,
                                                                                           Integer waitingSlaOverride,
                                                                                           Integer transitSlaOverride) {
        int max = limit == null || limit < 1 ? 100 : Math.min(limit, 500);
                AdminOpsOverviewResponse overview = buildOpsOverview(period, from, to, 100, max, waitingSlaOverride, transitSlaOverride);
        return AdminOpsAlertsResponse.builder()
                .generatedAt(overview.getGeneratedAt())
                .period(overview.getPeriod())
                .periodStart(overview.getPeriodStart())
                .periodEnd(overview.getPeriodEnd())
                .sla(overview.getSla())
                .alerts(overview.getExceptions())
                .build();
    }

        @Transactional(readOnly = true)
        public AdminOpsAlertsResponse getOpsAlerts(String period, LocalDate from, LocalDate to, Integer limit) {
                return getOpsAlerts(period, from, to, limit, null, null);
        }

        @Transactional(readOnly = true)
        public AdminOpsAuditResponse getOpsAudit(String period,
                                             LocalDate from,
                                             LocalDate to,
                                             Integer limit,
                                             String actor,
                                             String role,
                                             DeliveryStatus status) {
        StatsRange range = resolveRange(period, from, to);
        return doBuildAudit(range, limit, actor, role, status);
    }

    @Transactional(readOnly = true)
    public AdminOpsAuditResponse doBuildAudit(StatsRange range, Integer limit, String actor, String role, DeliveryStatus status) {
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
                            .eventKey(h.getEventKey())
                            .eventParams(deserializeEventParams(h.getEventParams()))
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

        @Transactional(readOnly = true)
        public AdminOpsExceptionsResponse getOpsExceptions(String period,
                                                       LocalDate from,
                                                       LocalDate to,
                                                       Integer limit,
                                                       String motif,
                                                       UUID driverId,
                                                       String zone) {
        StatsRange range = resolveRange(period, from, to);
        List<Delivery> deliveries = doFetchDeliveriesForExceptions(range, driverId);

        // Bulk-fetch driver info OUTSIDE transaction
        Map<String, DriverDTO> driverMap = loadDriverMap(deliveries);
        Map<UUID, RouteInfo> routeInfoByDeliveryId = loadRouteInfoMap(deliveries);

        Set<UUID> zoneIds = deliveries.stream()
                .map(Delivery::getOrder)
                .filter(Objects::nonNull)
                .map(Order::getZoneId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, String> zoneNameById = fetchZoneNames(zoneIds);

        LocalDateTime now = LocalDateTime.now();
        String motifQuery = StringUtils.hasText(motif) ? motif.trim().toLowerCase(Locale.ROOT) : null;
        String zoneQuery = StringUtils.hasText(zone) ? zone.trim().toLowerCase(Locale.ROOT) : null;

        List<AdminOpsExceptionsResponse.ExceptionItem> filteredItems = deliveries.stream()
                .map(delivery -> exceptionClassifier.toExceptionItem(delivery, driverMap, routeInfoByDeliveryId, zoneNameById, now))
                .filter(Objects::nonNull)
                .filter(item -> {
                    if (motifQuery == null) return true;
                    return containsIgnoreCase(item.getMotif(), motifQuery)
                            || containsIgnoreCase(item.getComment(), motifQuery)
                            || containsIgnoreCase(item.getOrderRef(), motifQuery)
                            || containsIgnoreCase(item.getDeliveryId().toString(), motifQuery);
                })
                .filter(item -> zoneQuery == null || containsIgnoreCase(item.getZoneName(), zoneQuery))
                .sorted((a, b) -> {
                    int severityOrder = exceptionClassifier.severityScore(b.getSeverity()) - exceptionClassifier.severityScore(a.getSeverity());
                    if (severityOrder != 0) return severityOrder;
                    LocalDateTime aTime = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
                    LocalDateTime bTime = b.getUpdatedAt() != null ? b.getUpdatedAt() : b.getCreatedAt();
                    return (bTime != null ? bTime : LocalDateTime.MIN).compareTo(aTime != null ? aTime : LocalDateTime.MIN);
                })
                .toList();

        int max = limit == null || limit < 1 ? 50 : Math.min(limit, 200);
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

    @Transactional(readOnly = true)
    public List<Delivery> doFetchDeliveriesForExceptions(StatsRange range, UUID driverId) {
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

        return entityManager.createQuery(cq)
                .setMaxResults(1000)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> fetchZoneNames(Set<UUID> zoneIds) {
        if (zoneIds.isEmpty()) return Map.of();
        return zoneRepository.findAll().stream()
                .filter(z -> zoneIds.contains(z.getId()))
                .collect(Collectors.toMap(Zone::getId, Zone::getName));
    }
    private AdminOpsOverviewResponse buildOpsOverview(String period,
                                                      LocalDate from,
                                                      LocalDate to,
                                                      int topItems,
                                                      int alertLimit,
                                                      Integer waitingSlaOverride,
                                                      Integer transitSlaOverride) {
        int waitingSlaMins = systemSettingsService.getInt("ops.sla.waiting-limit-minutes", waitingLimitMinutes);
        int assignSlaMins = systemSettingsService.getInt("ops.sla.assign-limit-minutes", assignLimitMinutes);
        int pickupSlaMins = systemSettingsService.getInt("ops.sla.pickup-limit-minutes", 6);

        StatsRange range = resolveRange(period, from, to);
        int effectiveWaitingSlaMinutes = normalizeSlaThreshold(waitingSlaOverride, waitingSlaMins, "waitingSlaMinutes");

        // 1. Transactional DB fetch
        List<Delivery> scopedDeliveries = doFetchDeliveries(range);

        // 2. HTTP/External calls (OUTSIDE transaction)
        Map<String, DriverDTO> driverMap = loadDriverMap(scopedDeliveries);
        Map<UUID, RouteInfo> routeInfoByDeliveryId = loadRouteInfoMap(scopedDeliveries);

        List<AdminDeliverySummaryResponse> summaries = scopedDeliveries.stream()
                .map(d -> {
                    DriverDTO driver = d.getDriverId() != null ? driverMap.get(d.getDriverId().toString()) : null;
                    return toSummaryResponse(d, driver, routeInfoByDeliveryId.get(d.getId()));
                })
                .toList();

        LocalDateTime now = LocalDateTime.now();

        // Assignment lead-time breach: unassigned and past (scheduledAt − leadTime). Falls back to
        // legacy "since creation > threshold" only when the order has no ERP scheduled date.
        int assignLeadTimeMins = systemSettingsService.getInt("ops.sla.assign-leadtime-minutes", 120);
        long waitingBreaches = scopedDeliveries.stream()
                .filter(d -> d.getStatus() == DeliveryStatus.UNSCHEDULED)
                .filter(d -> {
                    LocalDateTime scheduledAt = d.getOrder() != null ? d.getOrder().effectiveScheduledAt() : null;
                    if (scheduledAt != null) {
                        return now.isAfter(scheduledAt.minusMinutes(assignLeadTimeMins));
                    }
                    LocalDateTime start = d.getOrder() != null ? d.getOrder().getCreatedAt() : d.getCreatedAt();
                    if (start == null) return false;
                    return java.time.Duration.between(start, now).toMinutes() > effectiveWaitingSlaMinutes;
                })
                .count();

        long assignBreaches = scopedDeliveries.stream()
                .filter(d -> d.getStatus() == DeliveryStatus.SCHEDULED)
                .filter(d -> {
                    if (d.getAssignedAt() == null) return false;
                    long diff = java.time.Duration.between(d.getAssignedAt(), now).toMinutes();
                    return diff > assignSlaMins;
                })
                .count();

        long pickupBreaches = scopedDeliveries.stream()
                .filter(d -> d.getStatus() == DeliveryStatus.PICKED_UP)
                .filter(d -> {
                    if (d.getPickedUpAt() == null) return false;
                    long diff = java.time.Duration.between(d.getPickedUpAt(), now).toMinutes();
                    return diff > pickupSlaMins;
                })
                .count();

        List<AdminOpsOverviewResponse.LaneSnapshot> lanes = List.of(
                buildLaneSnapshot(DeliveryStatus.UNSCHEDULED, "Planned", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.SCHEDULED, "Assigned", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.PICKED_UP, "Picked Up", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.IN_TRANSIT, "In Transit", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.DELIVERED, "Delivered", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.PARTIALLY_DELIVERED, "Partial", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.FAILED, "Failed", summaries, topItems),
                buildLaneSnapshot(DeliveryStatus.CANCELLED, "Cancelled", summaries, topItems)
        );

        List<AdminOpsOverviewResponse.ExceptionRow> exceptions = summaries.stream()
                .map(s -> exceptionClassifier.toExceptionRow(s, now, effectiveWaitingSlaMinutes, 0))
                .filter(Objects::nonNull)
                .sorted((a, b) -> {
                    int severityOrder = exceptionClassifier.severityScore(b.getSeverity()) - exceptionClassifier.severityScore(a.getSeverity());
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
                        .assignThresholdMinutes(assignSlaMins)
                        .pickupThresholdMinutes(pickupSlaMins)
                        .waitingBreaches(waitingBreaches)
                        .assignBreaches(assignBreaches)
                        .pickupBreaches(pickupBreaches)
                        .totalBreaches(waitingBreaches + assignBreaches + pickupBreaches)
                        .slaAtRisk(slaStateRepository.countByHealth(com.asm.delivery.sla.SlaHealth.AT_RISK))
                        .slaBreached(slaStateRepository.countByHealth(com.asm.delivery.sla.SlaHealth.BREACHED))
                        .build())
                .lanes(lanes)
                .exceptions(exceptions)
                .build();
    }

    @Transactional(readOnly = true)
    public List<Delivery> doFetchDeliveries(StatsRange range) {
        TypedQuery<Delivery> query = entityManager.createQuery(
                "SELECT d FROM Delivery d JOIN FETCH d.order o WHERE d.createdAt BETWEEN :start AND :end ORDER BY d.createdAt DESC",
                Delivery.class
        );
        query.setParameter("start", range.start());
        query.setParameter("end", range.end());
        query.setMaxResults(1000);
        return query.getResultList();
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
                        routeStop -> {
                            Route r = routeStop.getRoute();
                            return new RouteInfo(
                                r.getId(), 
                                r.getName(), 
                                r.getStatus(),
                                r.getStartedAt(),
                                r.getDepartureTime(),
                                r.getDate(),
                                r.getPlannedStartTime(),
                                routeStop.getStartTimeWindow(),
                                routeStop.getEndTimeWindow()
                            );
                        },
                        (existing, replacement) -> existing
                ));
    }

    private AdminDeliverySummaryResponse toSummaryResponse(Delivery d, DriverDTO driver, RouteInfo routeInfo) {
        Order order = d.getOrder();
        String orderRef = order != null ? order.resolveRef() : "-";

        boolean isDropoffPinned = order != null && order.getDropoffLat() != null && order.getDropoffLng() != null;
        Zone zone = (order != null && order.getZoneId() != null)
                ? zoneRepository.findById(order.getZoneId()).orElse(null)
                : null;
        return AdminDeliverySummaryResponse.builder()
                .deliveryId(d.getId())
                .orderId(order != null ? order.getId() : null)
                .orderRef(orderRef)
                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                .routeStatus(routeInfo != null && routeInfo.routeStatus() != null ? routeInfo.routeStatus().name() : null)
                .routeStartedAt(routeInfo != null ? routeInfo.startedAt() : null)
                .routeDepartureTime(routeInfo != null ? routeInfo.departureTime() : null)
                .routeDate(routeInfo != null ? routeInfo.date() : null)
                .routePlannedStartTime(routeInfo != null ? routeInfo.plannedStartTime() : null)
                .routeEndTimeWindow(routeInfo != null ? routeInfo.endTimeWindow() : null)
                .timeSlotStartTime(routeInfo != null && routeInfo.startTimeWindow() != null ? routeInfo.startTimeWindow().toString() : null)
                .timeSlotEndTime(routeInfo != null && routeInfo.endTimeWindow() != null ? routeInfo.endTimeWindow().toString() : null)
                .status(d.getStatus().name())
                .failureCode(d.getFailureCode() != null ? d.getFailureCode().name() : null)
                .failReason(d.getFailReason())
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
                .scheduledAt(order != null ? order.effectiveScheduledAt() : null)
                .rescheduledAt(order != null ? order.getRescheduledAt() : null)
                .createdAt(d.getCreatedAt())
                .assignedAt(d.getAssignedAt())
                .inTransitAt(d.getInTransitAt())
                .completedAt(d.getCompletedAt())
                .failedAt(d.getFailedAt())
                .cancelledAt(d.getCancelledAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }


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
                                                .orderRef(s.getOrderRef())
                                                .clientName(s.getClientName())
                                                .city(s.getDropoffCity())
                                                .driverName(s.getDriverName())
                                                .createdAt(s.getCreatedAt())
                                                .scheduledAt(s.getScheduledAt())
                                                .routeId(s.getRouteId())
                                                .build())
                                .toList();

                return AdminOpsOverviewResponse.LaneSnapshot.builder()
                                .status(status)
                                .label(label)
                                .count(filtered.size())
                                .items(items)
                                .build();
        }


        private boolean containsIgnoreCase(String value, String query) {
                return value != null && value.toLowerCase(Locale.ROOT).contains(query);
        }

        private Map<String, Object> deserializeEventParams(String json) {
                if (json == null || json.isEmpty()) return Map.of();
                try {
                        return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                        return Map.of();
                }
        }

        private ActorInfo resolveActor(UserPrincipal principal) {
                if (principal == null) {
                        return new ActorInfo("SYSTEM", Role.SYSTEM);
                }

                String actorName = StringUtils.hasText(principal.getDisplayName())
                                ? principal.getDisplayName().trim()
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
        private AdminStatsResponse.TodayStats buildTodayStats(LocalDateTime start, LocalDateTime end, String period) {
        long total     = countByCreatedAt(start, end);
        long delivered = countByField("completedAt", start, end);
        long failed    = countStatusWithin("createdAt", DeliveryStatus.FAILED, start, end);
        long inTransit = countStatusWithin("inTransitAt", DeliveryStatus.IN_TRANSIT, start, end);
        long waiting   = countStatusWithin("createdAt", DeliveryStatus.UNSCHEDULED, start, end);
        long assigned  = countStatusWithin("assignedAt", DeliveryStatus.SCHEDULED, start, end);
        long partial   = countStatusWithin("createdAt", DeliveryStatus.PARTIALLY_DELIVERED, start, end);
        double successRate = total > 0 ? ((double) delivered / total) * 100.0 : 0.0;
        double partialRate = total > 0 ? ((double) partial / total) * 100.0 : 0.0;

        return AdminStatsResponse.TodayStats.builder()
                .total(total).delivered(delivered).failed(failed)
                .inTransit(inTransit).waiting(waiting).assigned(assigned)
                .successRate(round2(successRate))
                .partialRate(round2(partialRate))
                .partialCount(partial)
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
                        case "all" -> {
                                yield new StatsRange("all", LocalDateTime.of(2000, 1, 1, 0, 0), now);
                        }
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
        // 1. Basic counts grouping by driverId
        TypedQuery<Object[]> countQuery = entityManager.createQuery(
                "SELECT d.driverId, COUNT(d), " +
                        "SUM(CASE WHEN d.status IN (:delivered, :partial) THEN 1 ELSE 0 END), " +
                        "SUM(CASE WHEN d.status = :failed THEN 1 ELSE 0 END) " +
                        "FROM Delivery d WHERE d.driverId IS NOT NULL AND d.createdAt BETWEEN :start AND :end " +
                        "GROUP BY d.driverId",
                Object[].class);
        countQuery.setParameter("delivered", DeliveryStatus.DELIVERED);
        countQuery.setParameter("partial", DeliveryStatus.PARTIALLY_DELIVERED);
        countQuery.setParameter("failed", DeliveryStatus.FAILED);
        countQuery.setParameter("start", start);
        countQuery.setParameter("end", end);

        Map<UUID, Object[]> countsMap = countQuery.getResultList().stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> row));

        // 2. Average delays grouping by driverId (strict stop delay, same as PDF)
        TypedQuery<Object[]> deliveryQuery = entityManager.createQuery(
                "SELECT d.id, d.driverId FROM Delivery d " +
                "WHERE d.driverId IS NOT NULL AND d.createdAt BETWEEN :start AND :end " +
                "AND d.status IN (:delivered, :partial)",
                Object[].class);
        deliveryQuery.setParameter("delivered", DeliveryStatus.DELIVERED);
        deliveryQuery.setParameter("partial", DeliveryStatus.PARTIALLY_DELIVERED);
        deliveryQuery.setParameter("start", start);
        deliveryQuery.setParameter("end", end);

        List<Object[]> deliveryRows = deliveryQuery.getResultList();
        List<UUID> deliveryIds = deliveryRows.stream()
                .map(r -> (UUID) r[0])
                .filter(Objects::nonNull)
                .toList();

        Map<UUID, UUID> driverByDelivery = deliveryRows.stream()
                .filter(r -> r[0] != null && r[1] != null)
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (UUID) r[1], (a, b) -> a));

        Map<UUID, Double> delayMap = new HashMap<>();
        if (!deliveryIds.isEmpty()) {
            Map<UUID, RouteStop> stopByDelivery = routeStopRepository.findAllByDeliveryIdInWithRoute(deliveryIds)
                    .stream()
                    .collect(Collectors.toMap(
                            RouteStop::getDeliveryId,
                            s -> s,
                            (a, b) -> {
                                if (a.getCreatedAt() == null) return b;
                                if (b.getCreatedAt() == null) return a;
                                return a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b;
                            }
                    ));

            Map<UUID, List<Integer>> delaysByDriver = new HashMap<>();
            for (RouteStop stop : stopByDelivery.values()) {
                UUID deliveryId = stop.getDeliveryId();
                UUID driverId = deliveryId != null ? driverByDelivery.get(deliveryId) : null;
                if (driverId == null) continue;
                Integer delay = delayCalculationService.calculateStrictStopDelayMinutes(stop, stop.getRoute());
                if (delay == null) continue;
                delaysByDriver.computeIfAbsent(driverId, k -> new ArrayList<>())
                        .add(Math.max(0, delay));
            }

            for (Map.Entry<UUID, List<Integer>> entry : delaysByDriver.entrySet()) {
                double avg = entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0.0);
                delayMap.put(entry.getKey(), avg);
            }
        }

        return countsMap.entrySet().stream()
                .map(entry -> {
                    UUID driverId = entry.getKey();
                    Object[] row  = entry.getValue();
                    long total    = row[1] != null ? ((Number) row[1]).longValue() : 0;
                    long del      = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    long fail     = row[3] != null ? ((Number) row[3]).longValue() : 0;
                    double sr     = total > 0 ? ((double) del / total) * 100.0 : 0.0;
                    double avgDelay = delayMap.getOrDefault(driverId, 0.0);

                    return AdminStatsResponse.DriverStats.builder()
                            .driverId(driverId != null ? driverId.toString() : null)
                            .driverName(null) // Will be enriched outside transaction
                            .total(total).delivered(del).failed(fail).successRate(round2(sr))
                            .avgDelayMinutes(round2(avgDelay))
                            .build();
                })
                .toList();
    }

    /**
     * Enriches the stats response with driver names from the Transport microservice.
     * This is done outside the database transaction to prevent connection pool exhaustion.
     */
    public void enrichDriverNames(AdminStatsResponse response) {
        if (response == null || response.getByDriver() == null) return;

        for (AdminStatsResponse.DriverStats ds : response.getByDriver()) {
            if (ds.getDriverId() != null) {
                try {
                    DriverDTO dto = transportPort.getDriver(ds.getDriverId());
                    if (dto != null) {
                        ds.setDriverName(dto.getName());
                    }
                } catch (Exception e) {
                    ds.setDriverName("Livreur " + ds.getDriverId().substring(0, 8));
                }
            }
        }
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

    private String shortDeliveryId(UUID deliveryId) {
        if (deliveryId == null) {
            return "UNKNOWN";
        }
        String raw = deliveryId.toString();
        return raw.length() <= 8 ? raw : raw.substring(0, 8);
    }



    @SuppressWarnings("unchecked")
    private List<AdminStatsResponse.ItemStats> buildTopItems(LocalDateTime start, LocalDateTime end, int limit) {
        String sql = "SELECT item->>'sku' AS sku, item->>'name' AS name, SUM((item->>'quantityDone')::int) AS total " +
                     "FROM deliveries d " +
                     "JOIN orders o ON d.order_id = o.id, " +
                     "LATERAL jsonb_array_elements(o.items) AS item " +
                     "WHERE d.status IN ('DELIVERED', 'PARTIALLY_DELIVERED') AND item->>'outcome' = 'DELIVERED' " +
                     "AND d.completed_at BETWEEN :start AND :end " +
                     "GROUP BY item->>'sku', item->>'name' " +
                     "ORDER BY total DESC";
        try {
            List<Object[]> rows = entityManager.createNativeQuery(sql)
                     .setParameter("start", start)
                     .setParameter("end", end)
                     .setMaxResults(limit)
                     .getResultList();

            return rows.stream()
                    .map(row -> AdminStatsResponse.ItemStats.builder()
                            .sku(row[0] != null ? row[0].toString() : "N/A")
                            .name(row[1] != null ? row[1].toString() : "Unknown")
                            .count(row[2] != null ? ((Number) row[2]).longValue() : 0)
                            .build())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private record ActorInfo(String name, Role role) {}
}
