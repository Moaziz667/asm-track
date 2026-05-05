package com.asm.delivery.service;

import com.asm.delivery.repository.RouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SlaMonitoringService {

    private final RouteRepository routeRepository;

    /**
     * Runs every 30 seconds (configurable).
     * For all IN_PROGRESS routes: recomputes SLA status and fires WebSocket alerts.
     */
    @Scheduled(fixedDelayString = "${app.sla.check-interval-ms:30000}")
    @Transactional
    public void checkSlaStatuses() {
        // Legacy ETA/buffer SLA monitoring is intentionally disabled.
        // Strict SLA is now evaluated from actual route stop completion data.
    }
}
