package com.asm.delivery.service;

import com.asm.delivery.entity.*;
import com.asm.delivery.repository.RouteAlertRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.repository.RouteStopRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlaMonitoringService {

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final RouteAlertRepository routeAlertRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Value("${app.sla.approaching-threshold-minutes:10}")
    private int approachingThresholdMinutes;

    @Value("${app.route.sla-buffer-minutes:30}")
    private int slaBufferMinutes;

    /**
     * Runs every 30 seconds (configurable).
     * For all IN_PROGRESS routes: recomputes SLA status and fires WebSocket alerts.
     */
    @Scheduled(fixedDelayString = "${app.sla.check-interval-ms:30000}")
    @Transactional
    public void checkSlaStatuses() {
        List<Route> activeRoutes = routeRepository.findByStatusIn(List.of(RouteStatus.IN_PROGRESS));
        if (activeRoutes.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        for (Route route : activeRoutes) {
            try {
                processRoute(route, now);
            } catch (Exception ex) {
                log.warn("SLA monitoring error for route {}: {}", route.getId(), ex.getMessage());
            }
        }
    }

    private void processRoute(Route route, LocalDateTime now) {
        List<RouteStop> stops = routeStopRepository.findByRouteIdOrderByStopOrderAsc(route.getId());

        for (RouteStop stop : stops) {
            if (isTerminal(stop.getStatus())) continue;
            if (stop.getEtaAt() == null || stop.getSlaDeadline() == null) continue;

            SlaStatus newStatus = RouteOptimizationService.computeSlaStatus(stop, now);
            AlertType alertType = determineAlertType(stop, now, newStatus);

            // Update stop SLA status if changed
            if (newStatus != stop.getSlaStatus()) {
                stop.setSlaStatus(newStatus);
                routeStopRepository.save(stop);
            }

            // Fire alert only if not already open for this stop + type
            if (alertType != null && !alertAlreadyOpen(route.getId(), stop.getId(), alertType)) {
                RouteAlert alert = RouteAlert.builder()
                        .routeId(route.getId())
                        .stopId(stop.getId())
                        .stopOrder(stop.getStopOrder())
                        .alertType(alertType)
                        .message(buildMessage(alertType, stop, now))
                        .build();
                routeAlertRepository.save(alert);
                pushAlert(route.getId(), alert);
            }
        }
    }

    private AlertType determineAlertType(RouteStop stop, LocalDateTime now, SlaStatus status) {
        if (status == SlaStatus.BREACHED) return AlertType.BREACHED;
        if (status == SlaStatus.AT_RISK) return AlertType.AT_RISK;

        // APPROACHING: on time but within threshold of ETA
        if (status == SlaStatus.ON_TIME && stop.getEtaAt() != null) {
            long minutesUntilEta = java.time.Duration.between(now, stop.getEtaAt()).toMinutes();
            if (minutesUntilEta >= 0 && minutesUntilEta <= approachingThresholdMinutes) {
                return AlertType.APPROACHING;
            }
        }
        return null;
    }

    private String buildMessage(AlertType type, RouteStop stop, LocalDateTime now) {
        return switch (type) {
            case BREACHED -> {
                long minsOverdue = java.time.Duration.between(stop.getSlaDeadline(), now).toMinutes();
                yield "Stop #" + stop.getStopOrder() + " — SLA BREACHED — " + minsOverdue + " min overdue";
            }
            case AT_RISK -> {
                long minsUntilBreach = java.time.Duration.between(now, stop.getSlaDeadline()).toMinutes();
                long minsPastEta = java.time.Duration.between(stop.getEtaAt(), now).toMinutes();
                yield "Stop #" + stop.getStopOrder() + " — Driver is " + minsPastEta
                        + " min past ETA, SLA breaches in " + minsUntilBreach + " min";
            }
            case APPROACHING -> {
                long minsUntilEta = java.time.Duration.between(now, stop.getEtaAt()).toMinutes();
                yield "Stop #" + stop.getStopOrder() + " — Approaching ETA in " + minsUntilEta + " min";
            }
        };
    }

    private boolean alertAlreadyOpen(UUID routeId, UUID stopId, AlertType alertType) {
        return routeAlertRepository.existsByRouteIdAndStopIdAndAlertTypeAndAcknowledgedFalse(
                routeId, stopId, alertType);
    }

    private void pushAlert(UUID routeId, RouteAlert alert) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/route/" + routeId + "/alerts",
                    new AlertPayload(
                            alert.getId(),
                            alert.getStopId(),
                            alert.getStopOrder(),
                            alert.getAlertType().name(),
                            alert.getMessage(),
                            alert.getCreatedAt()
                    )
            );
        } catch (Exception ex) {
            log.debug("WebSocket push failed for route {}: {}", routeId, ex.getMessage());
        }
    }

    private static boolean isTerminal(RouteStopStatus status) {
        return status == RouteStopStatus.COMPLETED
                || status == RouteStopStatus.FAILED
                || status == RouteStopStatus.PARTIAL;
    }

    public record AlertPayload(
            UUID alertId,
            UUID stopId,
            Integer stopOrder,
            String alertType,
            String message,
            LocalDateTime createdAt
    ) {}
}
