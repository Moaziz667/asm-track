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

    @Scheduled(fixedDelayString = "${app.sla.check-interval-ms:20000}")
    @Transactional
    public void checkSlaStatuses() {
        processSla();
    }

    private void processSla() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        
        // Fetch company-specific limits
        int waitingLimit = settings.getInt("ops.sla.waiting-limit-minutes", 15);
        int assignLimit = settings.getInt("ops.sla.assign-limit-minutes", 20);
        int pickupLimit = settings.getInt("ops.sla.pickup-limit-minutes", 15);

        // 1. Unscheduled SLA (Waiting)
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.UNSCHEDULED).forEach(d -> {
            long elapsedSeconds = java.time.Duration.between(d.getCreatedAt(), now).getSeconds();
            if (elapsedSeconds > (waitingLimit * 60L)) {
                if (alertedKeys.add(d.getId() + ":WAITING")) {
                    eventPublisher.publishSlaBreach(d, "SLA_WAITING", "WARNING", 
                        java.util.Map.of("elapsed", elapsedSeconds / 60, "limit", waitingLimit));
                }
            }
        });

        // 2. Assignment SLA (Délai de Démarrage)
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.SCHEDULED).forEach(d -> {
            java.time.LocalDateTime baseline = d.getAssignedAt();
            if (baseline == null) baseline = d.getCreatedAt();
            long elapsedSeconds = java.time.Duration.between(baseline, now).getSeconds();
            if (elapsedSeconds > (assignLimit * 60L)) {
                if (alertedKeys.add(d.getId() + ":ASSIGNMENT")) {
                    eventPublisher.publishSlaBreach(d, "SLA_ASSIGNMENT", "CRITICAL", 
                        java.util.Map.of("elapsed", elapsedSeconds / 60, "limit", assignLimit));
                }
            }
        });

        // 3. Pickup SLA (Délai de Départ)
        deliveryRepository.findByStatus(com.asm.delivery.entity.DeliveryStatus.PICKED_UP).forEach(d -> {
            java.time.LocalDateTime baseline = d.getPickedUpAt();
            if (baseline == null) baseline = d.getAssignedAt();
            if (baseline == null) baseline = d.getCreatedAt();
            long elapsedSeconds = java.time.Duration.between(baseline, now).getSeconds();
            if (elapsedSeconds > (pickupLimit * 60L)) {
                if (alertedKeys.add(d.getId() + ":PICKUP")) {
                    eventPublisher.publishSlaBreach(d, "SLA_PICKUP", "CRITICAL", 
                        java.util.Map.of("elapsed", elapsedSeconds / 60, "limit", pickupLimit));
                }
            }
        });

        // 4. Transit SLA
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
