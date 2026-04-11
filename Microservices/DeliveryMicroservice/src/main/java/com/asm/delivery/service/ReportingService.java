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

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportingService {

    private final DeliveryRepository deliveryRepository;
    private final RouteStopRepository routeStopRepository;
    private final AuditLogRepository auditLogRepository;
    private final DelayCalculationService delayCalculationService;
    private final ZoneRepository zoneRepository;

    public DashboardKpiResponse getGlobalKpis() {
        List<Delivery> allDeliveries = deliveryRepository.findAll();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();

        // 1. Volumes
        long ordersToday = allDeliveries.stream()
                .filter(d -> d.getCreatedAt() != null && !d.getCreatedAt().isBefore(todayStart))
                .count();

        // 2. Performance
        List<Delivery> completedDeliveries = allDeliveries.stream()
                .filter(d -> d.getStatus() == DeliveryStatus.DELIVERED || d.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED)
                .toList();
        long totalCompleted = completedDeliveries.size();

        Map<UUID, RouteStop> routeStopByDeliveryId = loadRouteStopsByDeliveryId(completedDeliveries);
        List<SlaEvaluation> evaluations = completedDeliveries.stream()
                .map(d -> evaluateDeliverySla(d, routeStopByDeliveryId.get(d.getId()), now))
                .toList();

        long measurableCount = evaluations.stream().filter(SlaEvaluation::measurable).count();
        long onTimeCompleted = evaluations.stream().filter(e -> e.measurable() && e.onTime()).count();

        double avgDelay = evaluations.stream()
                .filter(SlaEvaluation::measurable)
                .map(SlaEvaluation::delayMinutes)
                .filter(v -> v != null)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        double slaRate = measurableCount == 0 ? 100.0 : (double) onTimeCompleted / measurableCount * 100.0;

        // 3. Zones les plus actives — groupés par nom de zone réel (via zoneId sur l'Order)
        Map<UUID, String> zoneNameById = zoneRepository.findAll().stream()
                .collect(Collectors.toMap(Zone::getId, Zone::getName));

        Map<String, Long> ordersByZone = allDeliveries.stream()
                .filter(d -> d.getOrder() != null && d.getOrder().getZoneId() != null
                        && zoneNameById.containsKey(d.getOrder().getZoneId()))
                .collect(Collectors.groupingBy(
                        d -> zoneNameById.get(d.getOrder().getZoneId()),
                        Collectors.counting()));

        // 4. Exception Tracking (Audit Logs)
        long reassignments = auditLogRepository.countAllByActionContaining("REASSIGN");
        long replannings = auditLogRepository.countAllByActionContaining("REPLAN");

        // 5. Trend
        Map<String, Long> weeklyTrendMap = allDeliveries.stream()
                .filter(d -> d.getCreatedAt() != null && d.getCreatedAt().isAfter(now.minusDays(30)))
                .collect(Collectors.groupingBy(
                        d -> d.getCreatedAt().toLocalDate().toString(),
                        TreeMap::new,
                        Collectors.counting()
                ));

        List<DashboardKpiResponse.DailyVolume> trend = weeklyTrendMap.entrySet().stream()
                .map(e -> DashboardKpiResponse.DailyVolume.builder()
                        .date(e.getKey())
                        .count(e.getValue())
                        .build())
                .collect(Collectors.toList());

        return DashboardKpiResponse.builder()
                .avgDelayMinutes(avgDelay)
                .slaComplianceRate(slaRate)
                .totalCompleted(totalCompleted)
                .totalOrdersToday(ordersToday)
                .ordersByZone(ordersByZone)
                .totalReassigned(reassignments)
                .totalReplanned(replannings)
                .weeklyTrend(trend)
                .build();
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
                                                this::pickMostRecentRouteStop
                                ));
        }

        private RouteStop pickMostRecentRouteStop(RouteStop a, RouteStop b) {
                if (a.getRoute() == null) return b;
                if (b.getRoute() == null) return a;
                if (a.getRoute().getDate() == null) return b;
                if (b.getRoute().getDate() == null) return a;
                return a.getRoute().getDate().isAfter(b.getRoute().getDate()) ? a : b;
        }

        private SlaEvaluation evaluateDeliverySla(Delivery delivery, RouteStop stop, LocalDateTime now) {
                if (stop != null && stop.getRoute() != null) {
                        // Unified doctrine: manual time-window first, then ETA fallback.
                        LocalDateTime reference = delivery.getCompletedAt() != null ? delivery.getCompletedAt() : now;
                        SlaStatus status = RouteOptimizationService.computeSlaStatus(stop, reference);
                        Integer delayMinutes = resolveDelayMinutes(delivery, stop);
                        boolean onTime = status == SlaStatus.ON_TIME || status == SlaStatus.EARLY;
                        return new SlaEvaluation(true, onTime, delayMinutes);
                }

                if (delivery.getRouteEtaAt() != null) {
                        LocalDateTime completion = delivery.getCompletedAt() != null ? delivery.getCompletedAt() : now;
                        int delay = Math.max(0, (int) java.time.Duration.between(delivery.getRouteEtaAt(), completion).toMinutes());
                        return new SlaEvaluation(true, delay == 0, delay);
                }

                return new SlaEvaluation(false, false, null);
        }

        private Integer resolveDelayMinutes(Delivery delivery, RouteStop stop) {
                DelayCalculationService.DelayInfo delayInfo = delayCalculationService.calculateDelay(stop, stop.getRoute(), List.of(stop));
                if (delayInfo != null) {
                        return Math.max(delayInfo.delayMinutes, 0);
                }

                if (stop.getActualArrivalAt() != null && stop.getSlaDeadline() != null) {
                        return Math.max(0, (int) java.time.Duration.between(stop.getSlaDeadline(), stop.getActualArrivalAt()).toMinutes());
                }

                if (delivery.getCompletedAt() != null && delivery.getRouteEtaAt() != null) {
                        return Math.max(0, (int) java.time.Duration.between(delivery.getRouteEtaAt(), delivery.getCompletedAt()).toMinutes());
                }

                return 0;
        }

        private record SlaEvaluation(boolean measurable, boolean onTime, Integer delayMinutes) {
        }
}
