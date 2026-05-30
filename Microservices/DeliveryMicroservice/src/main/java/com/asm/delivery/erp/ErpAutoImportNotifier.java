package com.asm.delivery.erp;

import com.asm.delivery.erp.client.ErpAdapterClient;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.service.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Polls Odoo every 2 minutes for new orders not yet imported into ASM Track.
 * When new orders are detected, sends a WebSocket notification to the admin
 * so they can bulk-approve on the import page — no manual refresh needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpAutoImportNotifier {

    private final OrderRepository   orderRepository;
    private final ErpAdapterClient  erpAdapterClient;
    private final EventPublisher    eventPublisher;

    private int lastKnownCount = 0;

    @Scheduled(fixedDelayString = "${erp.notify.interval-ms:120000}")
    public void checkForNewOrders() {
        Set<String> alreadyImported = orderRepository.findAllErpOrderIds();

        try {
            List<Map<String, Object>> pending = erpAdapterClient.getPendingOrders(200, "odoo");

            int newCount = (int) pending.stream()
                    .map(m -> String.valueOf(m.getOrDefault("name", "")))
                    .filter(id -> !id.isBlank() && !alreadyImported.contains(id))
                    .count();

            if (newCount > lastKnownCount) {
                eventPublisher.publishErpOrdersReady(newCount);
                log.info("ErpAutoImportNotifier: {} new orders ready", newCount);
            }

            lastKnownCount = newCount;

        } catch (Exception e) {
            log.warn("ErpAutoImportNotifier: check failed: {}", e.getMessage());
        }
    }
}

