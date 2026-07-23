package com.asm.delivery.erp;

import com.asm.delivery.config.TenantIterator;
import com.asm.delivery.erp.port.ErpPort;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.service.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls each tenant's configured ERP every 2 minutes for new orders not yet imported into ASM Track.
 * When new orders are detected, sends a WebSocket notification to that tenant's admins so they can
 * bulk-approve on the import page — no manual refresh needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpAutoImportNotifier {

    private final OrderRepository   orderRepository;
    private final ErpPort           erpPort;
    private final EventPublisher    eventPublisher;
    private final TenantIterator    tenantIterator;

    // Last "new orders" count PER TENANT — a single shared counter would let one tenant's poll
    // suppress another's notification (and each tenant polls its own ERP with its own backlog).
    private final java.util.Map<UUID, Integer> lastKnownCountByTenant = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${erp.notify.interval-ms:120000}")
    public void checkForNewOrders() {
        // Per-tenant: forEachActive sets the TenantContext so orderRepository/erpPort/eventPublisher
        // all resolve the current tenant's schema, ERP provider and WS topic.
        tenantIterator.forEachActive(this::checkForNewOrdersForTenant);
    }

    private void checkForNewOrdersForTenant(UUID companyId) {
        Set<String> alreadyImported = orderRepository.findAllErpOrderIds();

        try {
            List<com.asm.delivery.erp.ErpPendingOrderSummaryDTO> pending = erpPort.getPendingOrders(200);

            int newCount = (int) pending.stream()
                    .map(com.asm.delivery.erp.ErpPendingOrderSummaryDTO::getErpOrderId)
                    .filter(id -> id != null && !id.isBlank() && !alreadyImported.contains(id))
                    .count();

            int lastKnown = lastKnownCountByTenant.getOrDefault(companyId, 0);
            if (newCount > lastKnown) {
                eventPublisher.publishErpOrdersReady(newCount);
                log.info("ErpAutoImportNotifier: {} new orders ready for tenant {}", newCount, companyId);
            }

            lastKnownCountByTenant.put(companyId, newCount);

        } catch (Exception e) {
            log.warn("ErpAutoImportNotifier: check failed for tenant {}: {}", companyId, e.getMessage());
        }
    }
}

