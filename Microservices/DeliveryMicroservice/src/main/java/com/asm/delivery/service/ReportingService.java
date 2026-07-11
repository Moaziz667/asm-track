package com.asm.delivery.service;

import com.asm.delivery.dto.response.DashboardKpiResponse;
import com.asm.delivery.dto.response.ZoneHeatmapResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.RouteStop;
import com.asm.delivery.entity.SlaStatus;
import com.asm.delivery.entity.Zone;
import com.asm.delivery.repository.AuditLogRepository;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteStopRepository;
import com.asm.delivery.repository.ZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportingService {

    private static final List<DeliveryStatus> COMPLETED_STATUSES =
            List.of(DeliveryStatus.DELIVERED, DeliveryStatus.PARTIALLY_DELIVERED);

    private final DeliveryRepository deliveryRepository;
    private final RouteStopRepository routeStopRepository;
    private final AuditLogRepository auditLogRepository;
    private final DelayCalculationService delayCalculationService;
    private final ZoneRepository zoneRepository;
    private final com.asm.delivery.service.analytics.filter.PeriodResolver periodResolver;
    private final jakarta.persistence.EntityManager entityManager;
    private final com.asm.delivery.service.analytics.ZoneResolver zoneResolver;

    /** Legacy entry point (period + date-only from/to) — delegates to the unified query. */
    public DashboardKpiResponse getGlobalKpis(String period, LocalDate from, LocalDate to) {
        com.asm.delivery.dto.analytics.AnalyticsQuery q = new com.asm.delivery.dto.analytics.AnalyticsQuery();
        q.setPeriod(period);
        if (from != null) q.setFrom(from.atStartOfDay());
        if (to != null) q.setTo(to.atTime(java.time.LocalTime.MAX));
        return getGlobalKpis(q);
    }

    public DashboardKpiResponse getGlobalKpis(com.asm.delivery.dto.analytics.AnalyticsQuery query) {
        LocalDateTime now = periodResolver.now();

        // 0. Resolve the current window (Tunis-anchored, granular) + the preceding window for deltas.
        com.asm.delivery.service.analytics.filter.PeriodRange pr = periodResolver.resolve(
                query.getRange() != null ? query.getRange() : query.getPeriod(),
                query.getLast(), query.getFrom(), query.getTo(), query.getGranularity(), true);
        final LocalDateTime start = pr.start();
        final LocalDateTime end = pr.end();

        // Server-side scope — the full pivot set (driver/zone/status/motif/city/source/depot)
        final com.asm.delivery.service.analytics.filter.AnalyticsFilter filter = toFilter(query);

        // 1. Volumes (SQL COUNT, indexed on the reference date)
        long ordersInPeriod = countInRange(start, end, filter);
        long previousPeriodOrders = pr.hasComparison()
                ? countInRange(pr.prevStart(), pr.prevEnd(), filter) : 0L;

        // 2. SLA / average delay — load only completed deliveries in each window
        SlaSummary current = summariseSla(start, end, now, filter);
        SlaSummary previous = pr.hasComparison()
                ? summariseSla(pr.prevStart(), pr.prevEnd(), now, filter) : current;

        // 2b. Delivered / failed counts (current + preceding window) for the top KPI deltas.
        long delivered = deliveredCount(start, end, filter);
        long failed = failedCount(start, end, filter);
        long previousDelivered = pr.hasComparison() ? deliveredCount(pr.prevStart(), pr.prevEnd(), filter) : 0L;
        long previousFailed = pr.hasComparison() ? failedCount(pr.prevStart(), pr.prevEnd(), filter) : 0L;

        // 3. Most active zones (SQL GROUP BY, mapped to names in memory)
        Map<UUID, String> zoneNameById = zoneRepository.findAll().stream()
                .collect(Collectors.toMap(Zone::getId, Zone::getName));
        Map<String, Long> ordersByZone = countByZone(start, end, filter).stream()
                .filter(row -> zoneNameById.containsKey((UUID) row[0]))
                .collect(Collectors.toMap(
                        row -> zoneNameById.get((UUID) row[0]),
                        row -> (Long) row[1],
                        Long::sum,
                        LinkedHashMap::new));

        // 4. Exception tracking (audit log counters)
        long reassignments = auditLogRepository.countAllByActionContaining("REASSIGN");
        long replannings = auditLogRepository.countAllByActionContaining("REPLAN");

        // 5. 30-day trend for the chart/sparklines — filtered if a scope is active.
        List<DashboardKpiResponse.DailyVolume> trend = buildTrend(now.toLocalDate().minusDays(29).atStartOfDay(), now, filter);

        return DashboardKpiResponse.builder()
                .avgDelayMinutes(current.avgDelay())
                .totalOrdersToday(ordersInPeriod)
                .previousPeriodOrders(previousPeriodOrders)
                .slaRate(current.slaRate())
                .previousSlaRate(previous.slaRate())
                .lateOrders(current.late())
                .measurableOrders(current.measurable())
                .deliveredOrders(delivered)
                .previousDelivered(previousDelivered)
                .failedOrders(failed)
                .previousFailed(previousFailed)
                .previousLate(previous.late())
                .ordersByZone(ordersByZone)
                .totalReassigned(reassignments)
                .totalReplanned(replannings)
                .weeklyTrend(trend)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<DashboardKpiResponse.DailyVolume> buildTrend(LocalDateTime start, LocalDateTime end,
                                                                com.asm.delivery.service.analytics.filter.AnalyticsFilter filter) {
        // Dynamic native query so the trend honors the full pivot set (incl. multi-select), event-anchored
        // like the KPI cards (failed_at ?? completed_at ?? created_at).
        jakarta.persistence.Query q = entityManager.createNativeQuery(
                "SELECT CAST(COALESCE(d.failed_at, d.completed_at, d.created_at) AS date) AS day, "
                        + "COUNT(*) AS total, "
                        + "COUNT(*) FILTER (WHERE d.status IN ('DELIVERED','PARTIALLY_DELIVERED')) AS delivered, "
                        + "COUNT(*) FILTER (WHERE d.failed_at IS NOT NULL) AS failed "
                        + "FROM deliveries d JOIN orders o ON d.order_id = o.id "
                        + "WHERE COALESCE(d.failed_at, d.completed_at, d.created_at) BETWEEN :start AND :end"
                        + filter.nativeSql()
                        + " GROUP BY day ORDER BY day")
                .setParameter("start", start).setParameter("end", end);
        filter.bindNative(q);
        List<Object[]> rows = q.getResultList();
        return rows.stream()
                .map(row -> DashboardKpiResponse.DailyVolume.builder()
                        .date(((java.sql.Date) row[0]).toLocalDate().toString())
                        .count(((Number) row[1]).longValue())
                        .delivered(((Number) row[2]).longValue())
                        .failed(((Number) row[3]).longValue())
                        .build())
                .collect(Collectors.toList());
    }

    private com.asm.delivery.service.analytics.filter.AnalyticsFilter toFilter(com.asm.delivery.dto.analytics.AnalyticsQuery q) {
        return new com.asm.delivery.service.analytics.filter.AnalyticsFilter(
                q.getDriverId(), zoneResolver.resolveAll(q.getZone()), q.getStatus(), q.getMotif(),
                q.getCity(), q.getSource(), q.getDepot());
    }

    /** Order density per zip/zone over a window (dashboard "densité par zone"). Same reference date
     *  and pivot scope as the KPI endpoint; the client aggregates points by zone. */
    public ZoneHeatmapResponse getZoneHeatmap(com.asm.delivery.dto.analytics.AnalyticsQuery query) {
        com.asm.delivery.service.analytics.filter.PeriodRange pr = periodResolver.resolve(
                query.getRange() != null ? query.getRange() : query.getPeriod(),
                query.getLast(), query.getFrom(), query.getTo(), query.getGranularity(), query.isCompare());
        com.asm.delivery.service.analytics.filter.AnalyticsFilter filter = toFilter(query);

        Map<UUID, Zone> zoneById = zoneRepository.findAll().stream()
                .collect(Collectors.toMap(Zone::getId, z -> z));

        // Comparison: order count per zone over the preceding window of equal length (Tunis-anchored),
        // so the client delta is meaningful for every preset (not the broken "last7d vs last30d" shift).
        Map<String, Long> previousOrdersByZone = new java.util.HashMap<>();
        if (pr.hasComparison()) {
            for (Object[] row : countByZone(pr.prevStart(), pr.prevEnd(), filter)) {
                UUID zid = (UUID) row[0];
                if (zid != null) previousOrdersByZone.merge(zid.toString(), (Long) row[1], Long::sum);
            }
        }

        List<ZoneHeatmapResponse.ZipcodeHeatpoint> points = zipcodeDensity(pr.start(), pr.end(), filter).stream()
                .map(r -> {
                    UUID zoneId = (UUID) r[5];
                    Zone z = zoneById.get(zoneId);
                    if (z == null) return null;
                    return ZoneHeatmapResponse.ZipcodeHeatpoint.builder()
                            .zipcode((String) r[0])
                            .lat(r[1] != null ? ((Number) r[1]).doubleValue() : 0.0)
                            .lng(r[2] != null ? ((Number) r[2]).doubleValue() : 0.0)
                            .ordersCount(((Number) r[3]).longValue())
                            .delayedOrders(((Number) r[4]).longValue())
                            .zoneId(zoneId)
                            .zoneName(z.getName())
                            .zoneColor(z.getColor())
                            .build();
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        return ZoneHeatmapResponse.builder().points(points).previousOrdersByZone(previousOrdersByZone).build();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> zipcodeDensity(LocalDateTime start, LocalDateTime end, com.asm.delivery.service.analytics.filter.AnalyticsFilter f) {
        jakarta.persistence.Query q = entityManager.createQuery(
                "SELECT d.order.dropoffPostalCode, AVG(d.order.dropoffLat), AVG(d.order.dropoffLng), "
                        + "COUNT(d), SUM(CASE WHEN d.failedAt IS NOT NULL THEN 1 ELSE 0 END), d.order.zoneId "
                        + "FROM Delivery d WHERE COALESCE(d.completedAt, d.createdAt) BETWEEN :start AND :end "
                        + "AND d.order.zoneId IS NOT NULL AND d.order.dropoffLat IS NOT NULL AND d.order.dropoffLng IS NOT NULL"
                        + f.jpql()
                        + " GROUP BY d.order.dropoffPostalCode, d.order.zoneId")
                .setParameter("start", start).setParameter("end", end);
        f.bind(q);
        return q.getResultList();
    }

    // ── Dynamic, full-pivot queries (reuse AnalyticsFilter; reference date = completedAt ?? createdAt) ──
    private long countInRange(LocalDateTime start, LocalDateTime end, com.asm.delivery.service.analytics.filter.AnalyticsFilter f) {
        jakarta.persistence.TypedQuery<Long> q = entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE COALESCE(d.completedAt, d.createdAt) BETWEEN :start AND :end" + f.jpql(), Long.class)
                .setParameter("start", start).setParameter("end", end);
        f.bind(q);
        return q.getSingleResult();
    }

    /** Delivered (incl. partial) in a window, anchored on completedAt (event-based, immutable). */
    private long deliveredCount(LocalDateTime start, LocalDateTime end, com.asm.delivery.service.analytics.filter.AnalyticsFilter f) {
        jakarta.persistence.TypedQuery<Long> q = entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE d.status IN :statuses AND d.completedAt BETWEEN :start AND :end" + f.jpql(), Long.class)
                .setParameter("statuses", COMPLETED_STATUSES).setParameter("start", start).setParameter("end", end);
        f.bind(q);
        return q.getSingleResult();
    }

    /** Failure events in a window, anchored on failedAt (survives replans → immutable). */
    private long failedCount(LocalDateTime start, LocalDateTime end, com.asm.delivery.service.analytics.filter.AnalyticsFilter f) {
        jakarta.persistence.TypedQuery<Long> q = entityManager.createQuery(
                "SELECT COUNT(d) FROM Delivery d WHERE d.failedAt BETWEEN :start AND :end" + f.jpql(), Long.class)
                .setParameter("start", start).setParameter("end", end);
        f.bind(q);
        return q.getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> countByZone(LocalDateTime start, LocalDateTime end, com.asm.delivery.service.analytics.filter.AnalyticsFilter f) {
        jakarta.persistence.Query q = entityManager.createQuery(
                "SELECT d.order.zoneId, COUNT(d) FROM Delivery d WHERE COALESCE(d.completedAt, d.createdAt) BETWEEN :start AND :end "
                        + "AND d.order.zoneId IS NOT NULL" + f.jpql() + " GROUP BY d.order.zoneId")
                .setParameter("start", start).setParameter("end", end);
        f.bind(q);
        return q.getResultList();
    }

    private List<Delivery> findCompleted(LocalDateTime start, LocalDateTime end, com.asm.delivery.service.analytics.filter.AnalyticsFilter f) {
        jakarta.persistence.TypedQuery<Delivery> q = entityManager.createQuery(
                "SELECT d FROM Delivery d WHERE d.status IN :statuses AND COALESCE(d.completedAt, d.createdAt) BETWEEN :start AND :end" + f.jpql(), Delivery.class)
                .setParameter("statuses", COMPLETED_STATUSES).setParameter("start", start).setParameter("end", end);
        f.bind(q);
        return q.getResultList();
    }

    /** SLA compliance + average delay over completed deliveries in a window (full pivot scope). */
    private SlaSummary summariseSla(LocalDateTime start, LocalDateTime end, LocalDateTime now, com.asm.delivery.service.analytics.filter.AnalyticsFilter filter) {
        List<Delivery> completed = findCompleted(start, end, filter);
        if (completed.isEmpty()) {
            return new SlaSummary(100.0, 0.0, 0L, 0L);
        }

        Map<UUID, RouteStop> stopByDeliveryId = loadRouteStopsByDeliveryId(completed);
        List<SlaEvaluation> evaluations = completed.stream()
                .map(d -> evaluateDeliverySla(d, stopByDeliveryId.get(d.getId()), now))
                .toList();

        long measurable = evaluations.stream().filter(SlaEvaluation::measurable).count();
        long onTime = evaluations.stream().filter(e -> e.measurable() && e.onTime()).count();
        double avgDelay = evaluations.stream()
                .filter(SlaEvaluation::measurable)
                .map(SlaEvaluation::delayMinutes)
                .filter(v -> v != null)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
        double slaRate = measurable == 0 ? 100.0 : (double) onTime / measurable * 100.0;
        long late = Math.max(0, measurable - onTime);
        return new SlaSummary(slaRate, avgDelay, measurable, late);
    }

    private Map<UUID, RouteStop> loadRouteStopsByDeliveryId(List<Delivery> deliveries) {
        List<UUID> deliveryIds = deliveries.stream().map(Delivery::getId).toList();
        if (deliveryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return routeStopRepository.findAllByDeliveryIdInWithRoute(deliveryIds).stream()
                .collect(Collectors.toMap(
                        RouteStop::getDeliveryId,
                        Function.identity(),
                        this::pickMostRecentRouteStop));
    }

    private RouteStop pickMostRecentRouteStop(RouteStop a, RouteStop b) {
        if (a.getRoute() == null) return b;
        if (b.getRoute() == null) return a;
        if (a.getRoute().getDate() == null) return b;
        if (b.getRoute().getDate() == null) return a;
        return a.getRoute().getDate().isAfter(b.getRoute().getDate()) ? a : b;
    }

    private SlaEvaluation evaluateDeliverySla(Delivery delivery, RouteStop stop, LocalDateTime now) {
        // Strict requirement: only measure SLA if manual windows are defined
        if (stop != null && stop.getStartTimeWindow() != null && stop.getEndTimeWindow() != null) {
            LocalDateTime reference = delivery.getCompletedAt() != null ? delivery.getCompletedAt() : now;
            SlaStatus status = RouteOptimizationService.computeSlaStatus(stop, reference);
            Integer delayMinutes = resolveDelayMinutes(delivery, stop);
            boolean onTime = status == SlaStatus.ON_TIME || status == SlaStatus.EARLY;
            return new SlaEvaluation(true, onTime, delayMinutes);
        }
        // If no windows, we don't measure SLA performance, even if an ETA exists.
        return new SlaEvaluation(false, false, null);
    }

    private Integer resolveDelayMinutes(Delivery delivery, RouteStop stop) {
        DelayCalculationService.DelayInfo delayInfo = delayCalculationService.calculateDelay(stop, stop.getRoute(), List.of(stop));
        if (delayInfo != null) {
            return Math.max(delayInfo.delayMinutes, 0);
        }
        if (stop.getActualArrivalAt() != null && stop.getEtaBufferAt() != null) {
            return Math.max(0, (int) Duration.between(stop.getEtaBufferAt(), stop.getActualArrivalAt()).toMinutes());
        }
        if (delivery.getCompletedAt() != null && delivery.getRouteEtaAt() != null) {
            return Math.max(0, (int) Duration.between(delivery.getRouteEtaAt(), delivery.getCompletedAt()).toMinutes());
        }
        return 0;
    }

    private record SlaEvaluation(boolean measurable, boolean onTime, Integer delayMinutes) {
    }

    private record SlaSummary(double slaRate, double avgDelay, long measurable, long late) {
    }
}
