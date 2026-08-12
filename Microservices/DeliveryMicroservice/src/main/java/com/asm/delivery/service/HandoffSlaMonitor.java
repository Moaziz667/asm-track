package com.asm.delivery.service;

import com.asm.tenant.TenantContext;

import com.asm.tenant.jpa.TenantIterator;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Handoff;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.HandoffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Server-side timer that escalates stalled handoffs. Clients receive only pushes
 * (no client polling): an open handoff older than {@code ops.handoff.pending-minutes}
 * triggers a one-time {@code handoff.overdue} admin alert + reminder to both drivers.
 * If {@code ops.handoff.auto-cancel-minutes} (>0) is exceeded, the handoff is expired
 * and the parcel returns to dispatch resolution.
 *
 * <p>Both thresholds come from the tenant's {@code system_settings} (editable in Settings, like the
 * delivery SLA thresholds), falling back to the {@code handoff.sla.*} properties. A city where every
 * transfer happens on foot in a souk and one where drivers meet on a ring road do not deserve the
 * same patience, and that is a per-tenant call rather than a redeploy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandoffSlaMonitor {

    private final HandoffRepository handoffRepo;
    private final DeliveryRepository deliveryRepo;
    private final EventPublisher eventPublisher;
    private final HandoffService handoffService;
    private final TenantIterator tenantIterator;
    private final SystemSettingsService systemSettingsService;
    // Self-proxy so the per-tenant @Transactional applies (a direct this-call would bypass it).
    private final ObjectProvider<HandoffSlaMonitor> self;

    /** Tenant-overridable settings keys — see {@link SystemSettingsService}. */
    public static final String PENDING_MINUTES_KEY = "ops.handoff.pending-minutes";
    public static final String AUTO_CANCEL_MINUTES_KEY = "ops.handoff.auto-cancel-minutes";

    @Value("${handoff.sla.pending-minutes:15}")
    private int pendingMinutes;

    @Value("${handoff.sla.auto-cancel-minutes:0}")
    private int autoCancelMinutes;

    /**
     * Escalates stalled handoffs once <b>per provisioned tenant</b> — the scheduled thread has no
     * TenantContext, so a single unscoped sweep would only see the empty {@code public} schema and no
     * tenant's overdue handoffs would ever be escalated. Each tenant runs in its own transaction.
     */
    @Scheduled(fixedDelayString = "${handoff.sla.check-interval-ms:30000}")
    public void sweep() {
        tenantIterator.forEachActive(companyId -> self.getObject().sweepCurrentTenant());
    }

    /** One tenant's overdue-handoff sweep. */
    @Transactional
    public void sweepCurrentTenant() {
        LocalDateTime now = LocalDateTime.now();
        int pending = systemSettingsService.getInt(PENDING_MINUTES_KEY, pendingMinutes);
        int autoCancel = systemSettingsService.getInt(AUTO_CANCEL_MINUTES_KEY, autoCancelMinutes);

        for (Handoff h : handoffRepo.findOverdue(now.minusMinutes(pending))) {
            h.setOverdueNotifiedAt(now);
            handoffRepo.save(h);
            Order order = deliveryRepo.findByIdWithOrder(h.getDeliveryId()).map(Delivery::getOrder).orElse(null);
            log.warn("Handoff overdue handoffId={} deliveryId={}", h.getId(), h.getDeliveryId());
            eventPublisher.publishHandoffOverdue(h, order);
        }

        if (autoCancel > 0) {
            for (Handoff h : handoffRepo.findOpenOlderThan(now.minusMinutes(autoCancel))) {
                handoffService.expire(h.getId(), "Handoff not confirmed within SLA");
            }
        }
    }
}
