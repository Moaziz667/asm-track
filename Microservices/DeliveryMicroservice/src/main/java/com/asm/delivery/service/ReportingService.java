package com.asm.delivery.service;

import com.asm.delivery.dto.response.DashboardKpiResponse;
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

        // Server-side scope (null = no filter)
        final UUID driverId = query.getDriverId();
        final UUID zoneId = resolveZoneId(query.getZone());

        // 1. Volumes (SQL COUNT, indexed on the reference date)
        long ordersInPeriod = deliveryRepository.countInRangeFiltered(start, end, driverId, zoneId);
        long previousPeriodOrders = pr.hasComparison()
                ? deliveryRepository.countInRangeFiltered(pr.prevStart(), pr.prevEnd(), driverId, zoneId) : 0L;

        // 2. SLA / average delay — load only completed deliveries in each window
        SlaSummary current = summariseSla(start, end, now, driverId, zoneId);
        SlaSummary previous = pr.hasComparison()
                ? summariseSla(pr.prevStart(), pr.prevEnd(), now, driverId, zoneId) : current;

        // 3. Most active zones (SQL GROUP BY, mapped to names in memory)
        Map<UUID, String> zoneNameById = zoneRepository.findAll().stream()
                .collect(Collectors.toMap(Zone::getId, Zone::getName));
        Map<String, Long> ordersByZone = deliveryRepository.countByZoneInRangeFiltered(start, end, driverId, zoneId).stream()
                .filter(row -> zoneNameById.containsKey((UUID) row[0]))
                .collect(Collectors.toMap(
                        row -> zoneNameById.get((UUID) row[0]),
                        row -> (Long) row[1],
                        Long::sum,
                        LinkedHashMap::new));

        // 4. Exception tracking (audit log counters)
        long reassignments = auditLogRepository.countAllByActionContaining("REASSIGN");
        long replannings = auditLogRepository.countAllByActionContaining("REPLAN");

        // 5. 30-day trend for the chart/sparklines — always last 30 days, one query.
        List<DashboardKpiResponse.DailyVolume> trend = buildTrend(now.toLocalDate().minusDays(29).atStartOfDay(), now);

        return DashboardKpiResponse.builder()
                .avgDelayMinutes(current.avgDelay())
                .totalOrdersToday(ordersInPeriod)
                .previousPeriodOrders(previousPeriodOrders)
                .slaRate(current.slaRate())
                .previousSlaRate(previous.slaRate())
                .ordersByZone(ordersByZone)
                .totalReassigned(reassignments)
                .totalReplanned(replannings)
                .weeklyTrend(trend)
                .build();
    }

    private List<DashboardKpiResponse.DailyVolume> buildTrend(LocalDateTime start, LocalDateTime end) {
        return deliveryRepository.dailySeries(start, end).stream()
                .map(row -> DashboardKpiResponse.DailyVolume.builder()
                        .date(((java.sql.Date) row[0]).toLocalDate().toString())
                        .count(((Number) row[1]).longValue())
                        .delivered(((Number) row[2]).longValue())
                        .failed(((Number) row[3]).longValue())
                        .build())
                .collect(Collectors.toList());
    }

    /** Resolve a zone name to its id; null/blank or unknown yields null (no filter). */
    private UUID resolveZoneId(String zoneName) {
        if (zoneName == null || zoneName.isBlank()) return null;
        return zoneRepository.findAll().stream()
                .filter(z -> zoneName.trim().equalsIgnoreCase(z.getName()))
                .map(Zone::getId).findFirst().orElse(null);
    }

    /** SLA compliance + average delay over completed deliveries in a window (optional driver/zone scope). */
    private SlaSummary summariseSla(LocalDateTime start, LocalDateTime end, LocalDateTime now, UUID driverId, UUID zoneId) {
        List<Delivery> completed = deliveryRepository.findCompletedInRangeFiltered(COMPLETED_STATUSES, start, end, driverId, zoneId);
        if (completed.isEmpty()) {
            return new SlaSummary(100.0, 0.0);
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
        return new SlaSummary(slaRate, avgDelay);
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

    private record SlaSummary(double slaRate, double avgDelay) {
    }
}
