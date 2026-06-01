package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.Handoff;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.HandoffRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Server-side timer that escalates stalled handoffs. Clients receive only pushes
 * (no client polling): an open handoff older than {@code handoff.sla.pending-minutes}
 * triggers a one-time {@code handoff.overdue} admin alert + reminder to both drivers.
 * If {@code handoff.sla.auto-cancel-minutes} (>0) is exceeded, the handoff is expired
 * and the parcel returns to dispatch resolution.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandoffSlaMonitor {

    private final HandoffRepository handoffRepo;
    private final DeliveryRepository deliveryRepo;
    private final EventPublisher eventPublisher;
    private final HandoffService handoffService;

    @Value("${handoff.sla.pending-minutes:15}")
    private long pendingMinutes;

    @Value("${handoff.sla.auto-cancel-minutes:0}")
    private long autoCancelMinutes;

    @Scheduled(fixedDelayString = "${handoff.sla.check-interval-ms:30000}")
    @Transactional
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();

        for (Handoff h : handoffRepo.findOverdue(now.minusMinutes(pendingMinutes))) {
            h.setOverdueNotifiedAt(now);
            handoffRepo.save(h);
            Order order = deliveryRepo.findByIdWithOrder(h.getDeliveryId()).map(Delivery::getOrder).orElse(null);
            log.warn("Handoff overdue handoffId={} deliveryId={}", h.getId(), h.getDeliveryId());
            eventPublisher.publishHandoffOverdue(h, order);
        }

        if (autoCancelMinutes > 0) {
            for (Handoff h : handoffRepo.findOpenOlderThan(now.minusMinutes(autoCancelMinutes))) {
                handoffService.expire(h.getId(), "Handoff not confirmed within SLA");
            }
        }
    }
}
