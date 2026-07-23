package com.asm.erpadapter.inbound;

import com.asm.erpadapter.client.TenantListClient;
import com.asm.erpadapter.dto.ErpOrderChangeDTO;
import com.asm.erpadapter.port.ErpChangePort;
import com.asm.erpadapter.routing.ErpProviderRouter;
import com.asm.erpadapter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    @Value("${erp.inbound.poll.enabled:true}")
    private boolean enabled;

    /** Poll cursor per tenant (companyId → the ERP's last-seen change stamp). */
    private final Map<String, String> cursorByTenant = new ConcurrentHashMap<>();

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

        String key = companyId.toString();
        String cursor = cursorByTenant.get(key);
        if (cursor == null) {
            // First tick for this tenant: seed the cursor at the ERP's "now" so we don't replay history.
            cursorByTenant.put(key, port.initialCursor());
            return;
        }

        List<ErpOrderChangeDTO> changes = port.fetchChanges(cursor, 100);
        if (changes.isEmpty()) return;

        String advanced = cursor;
        for (ErpOrderChangeDTO ch : changes) {
            forwarder.forward(ch.erpOrderId(), ch.changeType(), ch.payload(), ch.cursorToken());
            if (ch.cursorToken() != null) advanced = ch.cursorToken(); // changes are ascending by stamp
        }
        cursorByTenant.put(key, advanced);
        log.info("ERP change poll: tenant={} forwarded {} change(s), cursor now {}", companyId, changes.size(), advanced);
    }
}
