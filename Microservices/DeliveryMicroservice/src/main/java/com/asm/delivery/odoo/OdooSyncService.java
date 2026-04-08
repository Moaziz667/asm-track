package com.asm.delivery.odoo;

import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderSource;
import com.asm.delivery.dto.request.PartialDeliveryItem;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.odoo.sync.FullDeliverySync;
import com.asm.delivery.odoo.sync.PartialDeliverySync;
import com.asm.delivery.odoo.sync.FailedDeliverySync;
import com.asm.delivery.odoo.sync.CancelOrderSync;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class OdooSyncService {

    private static final String SYNCED = "SYNCED";
    private static final String PENDING_RETRY = "PENDING_RETRY";
    private static final String PENDING_CANCEL = "PENDING_CANCEL";

    private final OdooClient odooClient;
    private final ErpClientResolver erpClientResolver;
    private final OrderRepository orderRepo;

    private final FullDeliverySync fullDeliverySync;
    private final PartialDeliverySync partialDeliverySync;
    private final FailedDeliverySync failedDeliverySync;
    private final CancelOrderSync cancelOrderSync;
    private final AuditLogService auditLogService;

    // ── Sync order creation to Odoo (APP orders only) ─────────────────────────

    public void syncOrderCreation(Order order) {
        if (order.getSource() == OrderSource.ODOO) {
            log.info("Skipping Odoo order creation sync for ODOO source orderId={}", order.getId());
            return;
        }
        if (order.getSource() != OrderSource.APP) return;

        String erpClientId = order.getErpClientId();
        if (erpClientId == null || erpClientId.isBlank()) {
            erpClientId = erpClientResolver.resolve(order.getClientName(), order.getClientPhone());
            if (erpClientId != null && !erpClientId.isBlank()) {
                order.setErpClientId(erpClientId);
                orderRepo.save(order);
            }
        }

        if (erpClientId == null || erpClientId.isBlank()) {
            log.warn("Could not resolve ERP client, skipping sync. orderId={}", order.getId());
            return;
        }

        Integer partnerId;
        try {
            partnerId = Integer.parseInt(erpClientId);
        } catch (NumberFormatException e) {
            log.warn("Could not parse erpClientId='{}', skipping sync. orderId={}", erpClientId, order.getId());
            return;
        }

        log.info("Syncing order creation to Odoo — orderId={} partnerId={}", order.getId(), partnerId);

        odooClient.updatePartnerAddress(partnerId, order.getDropoffAddress(), order.getDropoffCity());

        Integer odooId = odooClient.createSaleOrder(partnerId, order.getItems(), order.getDeliveryInstructions());

        if (odooId == null) {
            throw new RuntimeException("Odoo sync failed for orderId=" + order.getId());
        }

        order.setErpOrderId(String.valueOf(odooId));
        orderRepo.save(order);
        
        auditLogService.logAction(null, "ODOO_ORDER_CREATED", order.getId().toString(), 
            String.format("Order synced to Odoo. Odoo ID: %s, Client: %s", odooId, order.getClientName()));
            
        log.info("Order creation synced to Odoo — orderId={} erpOrderId={}", order.getId(), odooId);
    }

    // ── Sync order cancellation to Odoo ───────────────────────────────────────

    public void syncOrderCancellation(Order order) {
        if (order.getErpOrderId() == null) return;

        Integer erpId = resolveErpSaleOrderId(order, "cancel");
        if (erpId == null) return;

        boolean success = cancelOrderSync.sync(order, erpId);
        order.setOdooSyncStatus(success ? SYNCED : PENDING_CANCEL);
        orderRepo.save(order);
    }

    // ── Sync full stock update to Odoo ────────────────────────────────────────

    public void syncStockUpdate(Order order) {
        if (order.getErpOrderId() == null) return;

        Integer erpId = resolveErpSaleOrderId(order, "stock update");
        if (erpId == null) return;

        boolean success = fullDeliverySync.sync(order, erpId);
        order.setOdooSyncStatus(success ? SYNCED : PENDING_RETRY);
        orderRepo.save(order);
    }

    // ── Sync partial stock update to Odoo ─────────────────────────────────────

    public void syncPartialStockUpdate(Order order, List<PartialDeliveryItem> partialItems) {
        if (order.getErpOrderId() == null) return;

        Integer erpId = resolveErpSaleOrderId(order, "partial stock update");
        if (erpId == null) return;

        boolean success = partialDeliverySync.sync(order, erpId, partialItems);
        order.setOdooSyncStatus(success ? SYNCED : PENDING_RETRY);
        orderRepo.save(order);
    }

    // ── Append failure note to Odoo sale order ────────────────────────────────

    public void syncFailure(Order order, String failureCode, String comment) {
        if (order.getErpOrderId() == null) return;

        Integer erpId = resolveErpSaleOrderId(order, "failure note");
        if (erpId == null) return;

        failedDeliverySync.sync(order, erpId, failureCode, comment);
    }

    // ── Retry Logic ───────────────────────────────────────────────────────────
    // Retry logic has been removed to strictly focus on the core 4 flows and prevent side effects.

    private Integer resolveErpSaleOrderId(Order order, String operation) {
        Integer erpId = odooClient.resolveSaleOrderId(order.getErpOrderId());
        if (erpId != null) return erpId;

        log.warn("Could not resolve erpOrderId='{}' for orderId={} during {} — skipping Odoo sync",
                order.getErpOrderId(), order.getId(), operation);
        return null;
    }
}
