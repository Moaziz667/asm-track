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
@Transactional(readOnly = true)
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
                                                        || containsIgnoreCase(item.getComment(), motifQuery)
                                                        || containsIgnoreCase(item.getOrderRef(), motifQuery)
                                                        || containsIgnoreCase(item.getDeliveryId().toString(), motifQuery);
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

        long waitingBreaches = scopedDeliveries.stream()
                .filter(d -> d.getStatus() == DeliveryStatus.UNSCHEDULED)
                .filter(d -> {
                    LocalDateTime start = d.getOrder() != null ? d.getOrder().getCreatedAt() : d.getCreatedAt();
                    if (start == null) return false;
                    long diff = java.time.Duration.between(start, now).toMinutes();
                    return diff > effectiveWaitingSlaMinutes;
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

        Map<UUID, RouteStop> stopsByDeliveryId = routeStopRepository.findAllByDeliveryIdInWithRoute(
                scopedDeliveries.stream().map(Delivery::getId).toList()
        ).stream().collect(Collectors.toMap(RouteStop::getDeliveryId, s -> s, (v1, v2) -> v1));

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
                        .assignThresholdMinutes(assignSlaMins)
                        .pickupThresholdMinutes(pickupSlaMins)
                        .waitingBreaches(waitingBreaches)
                        .assignBreaches(assignBreaches)
                        .pickupBreaches(pickupBreaches)
                        .totalBreaches(waitingBreaches + assignBreaches + pickupBreaches)
                        .build())
                .lanes(lanes)
                .exceptions(exceptions)
                .build();
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
        String erpId = order != null ? order.getErpOrderId() : null;
        String orderRef = (erpId != null && !erpId.isBlank())
                ? erpId
                : (order != null ? order.getId().toString().substring(0, 8).toUpperCase() : "-");

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
                                                .orderRef(s.getOrderRef())
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
                if ("UNSCHEDULED".equals(s.getStatus()) && s.getCreatedAt() != null) {
                        int waitingLimit = systemSettingsService.getInt("ops.sla.waiting-limit-minutes", waitingLimitMinutes);
                        long elapsed = Duration.between(s.getCreatedAt(), now).toMinutes();
                        if (elapsed > waitingLimit) {
                                return buildExceptionRow(s, DeliveryStatus.UNSCHEDULED, "WARNING", 
                                     String.format("SLA Planification dépassé : La commande n'est pas planifiée depuis %d minutes", elapsed));
                        }
                }
                if ("SCHEDULED".equals(s.getStatus())) {
                        int assignLimit = systemSettingsService.getInt("ops.sla.assign-limit-minutes", assignLimitMinutes);
                        Long elapsed = resolveAssignSlaElapsedMinutes(s, now);
                        if (elapsed == null) {
                                return null;
                        }
                        if (elapsed > assignLimit) {
                            return buildExceptionRow(s, DeliveryStatus.SCHEDULED, "CRITICAL", 
                                 "SLA Affectation dépassé : le livreur tarde à récupérer le colis au dépôt");
                        }
                }
                if ("IN_TRANSIT".equals(s.getStatus())) {
                        // Utilisation du créneau horaire fixe défini par le dispatcher (Window End)
                        Optional<RouteStop> stopOpt = routeStopRepository.findByDeliveryId(s.getDeliveryId());
                        if (stopOpt.isPresent() && stopOpt.get().getEndTimeWindow() != null) {
                            LocalDateTime deadline = LocalDateTime.of(now.toLocalDate(), stopOpt.get().getEndTimeWindow());
                            if (now.isAfter(deadline)) {
                                 return buildExceptionRow(s, DeliveryStatus.IN_TRANSIT, "CRITICAL", "Retard critique : Créneau horaire de livraison dépassé");
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
                                .orderRef(s.getOrderRef())
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
                String erpId = order != null ? order.getErpOrderId() : null;
                String orderRef = (erpId != null && !erpId.isBlank())
                        ? erpId
                        : (order != null ? order.getId().toString().substring(0, 8).toUpperCase() : "-");

                DriverDTO driver = delivery.getDriverId() != null ? driverMap.get(delivery.getDriverId().toString()) : null;
                RouteInfo routeInfo = routeInfoByDeliveryId.get(delivery.getId());

                return AdminOpsExceptionsResponse.ExceptionItem.builder()
                                .deliveryId(delivery.getId())
                                .orderId(order != null ? order.getId() : null)
                                .orderRef(orderRef)
                                .routeId(routeInfo != null ? routeInfo.routeId() : null)
                                .routeName(routeInfo != null ? routeInfo.routeName() : null)
                                .status(delivery.getStatus())
                                .failureCode(delivery.getFailureCode() != null ? delivery.getFailureCode().name() : null)
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
                if (status == DeliveryStatus.SCHEDULED) {
                        int effectiveAssignLimit = systemSettingsService.getInt("ops.sla.assign-limit-minutes", assignLimitMinutes);
                        Optional<RouteStop> currentStopOpt = routeStopRepository.findByDeliveryId(delivery.getId());
                        LocalDateTime baseline = delivery.getAssignedAt();

                        if (currentStopOpt.isPresent()) {
                                RouteStop currentStop = currentStopOpt.get();
                                Route route = currentStop.getRoute();
                                
                                // 1. Use Route reference if available
                                if (route != null) {
                                        baseline = route.getStartedAt();
                                        if (baseline == null) baseline = route.getDepartureTime();
                                        if (baseline == null && route.getDate() != null && route.getPlannedStartTime() != null) {
                                                baseline = route.getDate().atTime(route.getPlannedStartTime());
                                        }
                                }
                                
                                if (baseline == null) {
                                        baseline = delivery.getAssignedAt() != null ? delivery.getAssignedAt() : delivery.getCreatedAt();
                                }

                                Integer currentOrder = currentStop.getStopOrder();

                                if (route != null && currentOrder != null && currentOrder > 1) {
                                        RouteStop previousStop = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId()).stream()
                                                        .filter(stop -> stop.getStopOrder() != null && stop.getStopOrder() < currentOrder)
                                                        .max(Comparator.comparingInt(RouteStop::getStopOrder))
                                                        .orElse(null);

                                        if (previousStop != null && !isRouteStopFinished(previousStop.getStatus())) {
                                                return new ExceptionClassification(
                                                                "WARNING",
                                                        "SCHEDULED_MONITORING",
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
                        String motif = elapsed > effectiveAssignLimit ? "SLA_SCHEDULED" : "SCHEDULED_MONITORING";
                        String comment = elapsed > effectiveAssignLimit
                                        ? "SLA Ramassage dépassé : le livreur tarde à récupérer le colis"
                                        : "Livraison disponible : suivi de planification en cours";
                        return new ExceptionClassification("WARNING", motif, comment);
                }
                if (status == DeliveryStatus.CANCELLED) {
                        String comment = StringUtils.hasText(delivery.getCancelReason()) ? delivery.getCancelReason() : "Livraison annulée : révision requise";
                        return new ExceptionClassification("CRITICAL", "CANCELLED", comment);
                }
                if (status == DeliveryStatus.PARTIALLY_DELIVERED) {
                        return new ExceptionClassification("WARNING", "PARTIAL_DELIVERY", "Livraison partielle signalée");
                }
                if (status == DeliveryStatus.UNSCHEDULED && delivery.getCreatedAt() != null) {
                        long elapsed = Duration.between(delivery.getCreatedAt(), now).toMinutes();
                        if (elapsed > waitingSlaMinutes) {
                                return new ExceptionClassification("WARNING", "SLA_UNSCHEDULED", "SLA Planification dépassé");
                        }
                }
                if (status == DeliveryStatus.IN_TRANSIT) {
                        Optional<RouteStop> stopOpt = routeStopRepository.findByDeliveryId(delivery.getId());
                        if (stopOpt.isPresent() && stopOpt.get().getEndTimeWindow() != null) {
                                Route route = stopOpt.get().getRoute();
                                if (route != null && route.getDate() != null) {
                                        LocalDateTime deadline = route.getDate().atTime(stopOpt.get().getEndTimeWindow());
                                        if (now.isAfter(deadline)) {
                                                return new ExceptionClassification("CRITICAL", "SLA_IN_TRANSIT", "Créneau horaire de livraison dépassé");
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
                                .failureCode(delivery.getFailureCode() != null ? delivery.getFailureCode().name() : null)
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
        private AdminStatsResponse.TodayStats buildTodayStats(LocalDateTime start, LocalDateTime end, String period) {
        long total     = countByCreatedAt(start, end);
        long delivered = countByField("completedAt", start, end);
        long failed    = countStatusWithin("createdAt", DeliveryStatus.FAILED, start, end);
        long inTransit = countStatusWithin("inTransitAt", DeliveryStatus.IN_TRANSIT, start, end);
        long waiting   = countStatusWithin("createdAt", DeliveryStatus.UNSCHEDULED, start, end);
        long assigned  = countStatusWithin("assignedAt", DeliveryStatus.SCHEDULED, start, end);
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

        // 2. Average delays grouping by driverId
        // We join with RouteStop to get the actual delay metrics
        TypedQuery<Object[]> delayQuery = entityManager.createQuery(
                "SELECT d.driverId, rs.completedAt, rs.slaDeadline " +
                "FROM Delivery d JOIN RouteStop rs ON d.id = rs.deliveryId " +
                "WHERE d.driverId IS NOT NULL AND d.createdAt BETWEEN :start AND :end " +
                "AND d.status IN (:delivered, :partial) " +
                "AND rs.completedAt IS NOT NULL AND rs.slaDeadline IS NOT NULL",
                Object[].class);
        delayQuery.setParameter("delivered", DeliveryStatus.DELIVERED);
        delayQuery.setParameter("partial", DeliveryStatus.PARTIALLY_DELIVERED);
        delayQuery.setParameter("start", start);
        delayQuery.setParameter("end", end);

        Map<UUID, Double> delayMap = delayQuery.getResultList().stream()
                .collect(Collectors.groupingBy(
                        row -> (UUID) row[0],
                        Collectors.averagingDouble(row -> {
                            LocalDateTime completed = (LocalDateTime) row[1];
                            LocalDateTime deadline = (LocalDateTime) row[2];
                            long minutes = java.time.Duration.between(deadline, completed).toMinutes();
                            return Math.max(0.0, (double) minutes);
                        })
                ));

        return countsMap.entrySet().stream()
                .map(entry -> {
                    UUID driverId = entry.getKey();
                    Object[] row  = entry.getValue();
                    long total    = row[1] != null ? ((Number) row[1]).longValue() : 0;
                    long del      = row[2] != null ? ((Number) row[2]).longValue() : 0;
                    long fail     = row[3] != null ? ((Number) row[3]).longValue() : 0;
                    double sr     = total > 0 ? ((double) del / total) * 100.0 : 0.0;
                    double avgDelay = delayMap.getOrDefault(driverId, 0.0);

                    String driverName = null;
                    if (driverId != null) {
                        try {
                            DriverDTO dto = transportPort.getDriver(driverId.toString());
                            if (dto != null) driverName = dto.getName();
                        } catch (Exception e) {
                            driverName = "Chauffeur " + driverId.toString().substring(0, 8);
                        }
                    }

                    return AdminStatsResponse.DriverStats.builder()
                            .driverId(driverId != null ? driverId.toString() : null)
                            .driverName(driverName)
                            .total(total).delivered(del).failed(fail).successRate(round2(sr))
                            .avgDelayMinutes(round2(avgDelay))
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

    private record ExceptionClassification(String severity, String motif, String comment) {}
    private record ActorInfo(String name, Role role) {}
}
