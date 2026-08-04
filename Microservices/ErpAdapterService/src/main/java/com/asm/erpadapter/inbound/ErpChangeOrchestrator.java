package com.asm.erpadapter.inbound;

import com.asm.erpadapter.client.TenantListClient;
import com.asm.erpadapter.dto.ErpOrderChangeDTO;
import com.asm.erpadapter.port.ErpChangePort;
import com.asm.erpadapter.routing.ErpProviderRouter;
import com.asm.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Inbound ERP→ASM change polling, <b>multi-tenant and provider-agnostic</b>. Replaces the old
 * Odoo-only {@code ErpChangePoller}: every tick it fetches the tenant list from AppBackend, then for
 * each tenant sets the {@link TenantContext} and asks {@link ErpProviderRouter} for that tenant's
 * inbound-change adapter ({@link ErpChangePort}) — Odoo tenants get the Odoo adapter, ERPNext tenants
 * the ERPNext one, and a tenant with no ERP configured is skipped cleanly. Each tenant keeps its own
 * poll cursor, so their ERPs advance independently.
 *
 * <p>Forwarding uses {@link ErpChangeForwarder} → the delivery service; the Feign interceptor stamps
 * the current {@code X-Company-Id} so each change lands in the right tenant's schema.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ErpChangeOrchestrator {

    private final TenantListClient tenantList;
    private final ErpProviderRouter router;
    private final ErpChangeForwarder forwarder;
    private final com.asm.erpadapter.repository.ErpPollCursorRepository cursorRepository;

    @Value("${erp.inbound.poll.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${erp.inbound.poll.fixed-delay-ms:600000}", initialDelay = 60000)
    public void pollChangedOrders() {
        if (!enabled) return;

        List<UUID> tenants;
        try {
            tenants = tenantList.listTenants();
        } catch (Exception e) {
            log.warn("ERP change poll: could not fetch tenant list from app-backend (will retry): {}", e.getMessage());
            return;
        }
        if (tenants == null || tenants.isEmpty()) return;

        for (UUID companyId : tenants) {
            TenantContext.set(companyId);
            try {
                pollTenant(companyId);
            } catch (Exception e) {
                log.warn("ERP change poll failed for tenant {} (will retry next tick): {}", companyId, e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void pollTenant(UUID companyId) {
        // Provider-agnostic: the router picks the adapter from THIS tenant's configured ERP. Empty =
        // the tenant has no ERP configured (provider "none") — nothing to poll, and not an error.
        ErpChangePort port = router.getChange().orElse(null);
        if (port == null) return;

        // Cursor is DURABLE (per tenant, in the adapter DB): an in-memory cursor re-seeded at "now"
        // on restart permanently skipped every ERP change made while the adapter was down.
        var stored = cursorRepository.findById(companyId).orElse(null);
        if (stored == null) {
            // Genuinely first tick ever for this tenant: seed at the ERP's "now" so we don't replay history.
            saveCursor(companyId, port.initialCursor());
            return;
        }
        String cursor = stored.getCursorValue();

        List<ErpOrderChangeDTO> changes = port.fetchChanges(cursor, 100);
        if (changes.isEmpty()) return;

        String advanced = cursor;
        for (ErpOrderChangeDTO ch : changes) {
            forwarder.forward(ch.erpOrderId(), ch.changeType(), ch.payload(), ch.cursorToken());
            if (ch.cursorToken() != null) advanced = ch.cursorToken(); // changes are ascending by stamp
        }
        saveCursor(companyId, advanced);
        log.info("ERP change poll: tenant={} forwarded {} change(s), cursor now {}", companyId, changes.size(), advanced);
    }

    private void saveCursor(UUID companyId, String cursor) {
        cursorRepository.save(com.asm.erpadapter.entity.ErpPollCursor.builder()
                .tenantId(companyId)
                .cursorValue(cursor)
                .updatedAt(java.time.LocalDateTime.now())
                .build());
    }
}
