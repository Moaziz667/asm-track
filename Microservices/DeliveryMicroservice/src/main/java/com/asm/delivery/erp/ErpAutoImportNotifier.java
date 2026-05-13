package com.asm.delivery.erp;

import com.asm.delivery.entity.OrderSource;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Last known count of importable orders per company — detects deltas. */
    private final ConcurrentHashMap<UUID, Integer> lastKnownCount = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${erp.notify.interval-ms:120000}")
    public void checkForNewOrders() {
        List<UUID> companies = orderRepository.findDistinctCompanyIdsBySource(OrderSource.ODOO);
        if (companies.isEmpty()) return;

        Set<String> alreadyImported = orderRepository.findAllErpOrderIds();

        for (UUID companyId : companies) {
            try {
                List<Map<String, Object>> pending = erpAdapterClient.getPendingOrdersForCompany(200, companyId);

                int newCount = (int) pending.stream()
                        .map(m -> String.valueOf(m.getOrDefault("name", "")))
                        .filter(id -> !id.isBlank() && !alreadyImported.contains(id))
                        .count();

                int last = lastKnownCount.getOrDefault(companyId, 0);

                if (newCount > last) {
                    eventPublisher.publishErpOrdersReady(companyId, newCount);
                    log.info("ErpAutoImportNotifier: {} new orders ready for company {}", newCount, companyId);
                }

                lastKnownCount.put(companyId, newCount);

            } catch (Exception e) {
                log.warn("ErpAutoImportNotifier: check failed for company {}: {}", companyId, e.getMessage());
            }
        }
    }
}
