package com.asm.delivery.service;

import com.asm.delivery.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SlaMonitoringService {

    private final com.asm.delivery.repository.DeliveryRepository deliveryRepository;
    private final com.asm.delivery.repository.RouteStopRepository routeStopRepository;
    private final EventPublisher eventPublisher;
    private final SystemSettingsService settings;
    private final java.util.Set<String> alertedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${app.sla.check-interval-ms:60000}")
    @Transactional
    public void checkSlaStatuses() {
        // Get all companies that have deliveries
        java.util.List<java.util.UUID> companies = deliveryRepository.findAllCompanyIds();
        
        for (java.util.UUID companyId : companies) {
            try {
                com.asm.delivery.config.TenantContext.set(companyId.toString());
                processSlaForCompany();
            } finally {
                com.asm.delivery.config.TenantContext.clear();
            }
        }
    }

    private void processSlaForCompany() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        
        // Fetch company-specific limits (TenantContext is set)
        int waitingLimit = settings.getInt("ops.sla.waiting-limit-minutes", 15);
        int assignLimit = settings.getInt("ops.sla.assign-limit-minutes", 20);

        // 1. Unscheduled SLA
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.UNSCHEDULED).forEach(d -> {
            long elapsed = java.time.Duration.between(d.getCreatedAt(), now).toMinutes();
            if (elapsed > waitingLimit) {
                if (alertedKeys.add(d.getId() + ":WAITING")) {
                    eventPublisher.publishSlaBreach(d, "SLA_WAITING", "WARNING", 
                        java.util.Map.of("elapsed", elapsed, "limit", waitingLimit));
                }
            }
        });

        // 2. Assignment SLA
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.SCHEDULED).forEach(d -> {
            java.time.LocalDateTime baseline = d.getAssignedAt();
            if (baseline == null) baseline = d.getCreatedAt();
            long elapsed = java.time.Duration.between(baseline, now).toMinutes();
            if (elapsed > assignLimit) {
                if (alertedKeys.add(d.getId() + ":ASSIGNMENT")) {
                    eventPublisher.publishSlaBreach(d, "SLA_ASSIGNMENT", "CRITICAL", 
                        java.util.Map.of("elapsed", elapsed, "limit", assignLimit));
                }
            }
        });

        // 3. Transit SLA
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.IN_TRANSIT).forEach(d -> {
            routeStopRepository.findByDeliveryId(d.getId()).ifPresent(stop -> {
                if (stop.getEndTimeWindow() != null) {
                    java.time.LocalDateTime deadline = now.toLocalDate().atTime(stop.getEndTimeWindow());
                    if (now.isAfter(deadline)) {
                        if (alertedKeys.add(d.getId() + ":TRANSIT")) {
                            eventPublisher.publishSlaBreach(d, "SLA_TRANSIT", "CRITICAL", 
                                java.util.Map.of("deadline", stop.getEndTimeWindow().toString()));
                        }
                    }
                }
            });
        });
    }
}
