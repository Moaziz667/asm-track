package com.asm.delivery.service;

import com.asm.delivery.dto.response.DashboardKpiResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportingService {

    private final DeliveryRepository deliveryRepository;
    private final AuditLogRepository auditLogRepository;

    public DashboardKpiResponse getGlobalKpis() {
        List<Delivery> allDeliveries = deliveryRepository.findAll();
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);

        // 1. Volumes
        long ordersToday = allDeliveries.stream()
                .filter(d -> d.getCreatedAt().isAfter(todayStart))
                .count();

        // 2. Performance
        long totalCompleted = allDeliveries.stream()
                .filter(d -> d.getStatus() == DeliveryStatus.DELIVERED)
                .count();

        // On peut lier avec DelayCalculationService si on veut le temps réel
        double avgDelay = 0.0; 

        long onTimeCompleted = totalCompleted; // Placeholder logic

        double slaRate = totalCompleted == 0 ? 100.0 : (double) onTimeCompleted / totalCompleted * 100.0;

        // 3. Zones les plus actives (basé sur la ville de destination de l'Order)
        Map<String, Long> ordersByZone = allDeliveries.stream()
                .filter(d -> d.getOrder() != null && d.getOrder().getDropoffCity() != null)
                .collect(Collectors.groupingBy(d -> d.getOrder().getDropoffCity(), Collectors.counting()));

        // 4. Exception Tracking (Audit Logs)
        long reassignments = auditLogRepository.countAllByActionContaining("REASSIGN");
        long replannings = auditLogRepository.countAllByActionContaining("REPLAN");

        // 5. Trend
        Map<String, Long> weeklyTrendMap = allDeliveries.stream()
                .filter(d -> d.getCreatedAt().isAfter(LocalDateTime.now().minusDays(30)))
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
}
