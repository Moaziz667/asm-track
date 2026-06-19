package com.asm.delivery.erp;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Order;
import com.asm.delivery.entity.OrderItem;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.OrderRepository;
import com.asm.delivery.service.EventPublisher;
import com.asm.delivery.service.OrderGeocodingService;
import com.asm.delivery.service.dispatch.ExceptionResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * V2 — Applies a change made <em>inside</em> Odoo back to ASM (Odoo→ASM direction), so the two systems
 * stay in sync after import. Arrives via the Odoo poller (ErpAdapter) and funnels to {@link #apply}.
 *
 * <p><b>Conflict rule (the whole policy in one line):</b> the boundary is <b>PICKED_UP</b>.
 * <ul>
 *   <li>Before departure (UNSCHEDULED/SCHEDULED) → Odoo is master → we APPLY the change.</li>
 *   <li>From PICKED_UP onwards → ASM (the field reality) is master → we IGNORE the change and raise an
 *       admin "Odoo conflict" notification so a human resolves it (credit note, return…).</li>
 * </ul>
 *
 * <p>Idempotency / anti-replay: the inbound {@code odooWriteDate} is compared against
 * {@link Order#getLastSyncedAt()}; an older or equal change is ignored. Anti-loop: mutations here set
 * {@code lastSyncedAt} and never enqueue an ASM→Odoo sync.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErpInboundReconciliationService {

    private final OrderRepository orderRepo;
    private final DeliveryRepository deliveryRepo;
    private final ExceptionResolutionService exceptionResolutionService;
    private final OrderGeocodingService orderGeocodingService;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** Change kinds Odoo can report. */
    public enum ChangeType { CANCELLED, LINES, ADDRESS, DATE }

    /**
     * @param erpOrderId    the Odoo sale-order reference (e.g. "S00123" or its picking ref)
     * @param changeType    what changed in Odoo
     * @param payload       change-specific fields (new items / address / date)
     * @param odooWriteDate Odoo's write_date for anti-replay; null skips the guard
     */
    @Transactional
    public void apply(String erpOrderId, ChangeType changeType, Map<String, Object> payload, LocalDateTime odooWriteDate) {
        Order order = findOrder(erpOrderId);
        if (order == null) {
            log.info("ERP inbound: no imported order for erpOrderId={} — ignoring {}", erpOrderId, changeType);
            return;
        }

        // Anti-replay: ignore a change we've already seen (or older).
        if (odooWriteDate != null && order.getLastSyncedAt() != null
                && !odooWriteDate.isAfter(order.getLastSyncedAt())) {
            log.debug("ERP inbound: stale change for orderId={} (writeDate={} <= lastSynced={}) — ignoring",
                    order.getId(), odooWriteDate, order.getLastSyncedAt());
            return;
        }

        Delivery delivery = deliveryRepo.findFirstByOrderIdOrderByCreatedAtDesc(order.getId()).orElse(null);
        boolean departed = delivery != null && hasDeparted(delivery.getStatus());

        if (departed) {
            // ASM is master once the parcel is in the field — never override field reality.
            log.warn("ERP inbound CONFLICT: Odoo {} on orderId={} but delivery already {} — ignoring + alerting admin",
                    changeType, order.getId(), delivery.getStatus());
            eventPublisher.publishErpConflict(order, delivery.getId(), changeType.name());
            touchSynced(order, odooWriteDate);
            return;
        }

        // Before departure → Odoo wins.
        switch (changeType) {
            case CANCELLED -> applyCancellation(delivery, order);
            case LINES     -> applyLineChange(order, payload);
            case ADDRESS   -> applyAddressChange(order, payload);
            case DATE      -> applyDateChange(order, payload);
        }
        touchSynced(order, odooWriteDate);
    }

    private void applyCancellation(Delivery delivery, Order order) {
        if (delivery == null) {
            log.info("ERP inbound CANCELLED: orderId={} has no delivery — nothing to cancel", order.getId());
            return;
        }
        // Reuse the existing admin cancel path; it soft-cancels and keeps the audit trail.
        // syncToErp=false (anti-loop): Odoo already cancelled it, no need to push the cancellation back.
        exceptionResolutionService.cancelDelivery(delivery.getId(), "Annulée dans Odoo", false);
        log.info("ERP inbound CANCELLED applied — orderId={} deliveryId={}", order.getId(), delivery.getId());
    }

    @SuppressWarnings("unchecked")
    private void applyLineChange(Order order, Map<String, Object> payload) {
        Object itemsRaw = payload != null ? payload.get("items") : null;
        if (itemsRaw == null) return;
        try {
            List<OrderItem> newItems = objectMapper.convertValue(itemsRaw,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, OrderItem.class));
            order.setItems(newItems);
            int totalQty = newItems.stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();
            order.setTotalQuantity(totalQty);
            orderRepo.save(order);
            log.info("ERP inbound LINES applied — orderId={} lines={}", order.getId(), newItems.size());
        } catch (Exception e) {
            log.warn("ERP inbound LINES: could not parse items for orderId={}: {}", order.getId(), e.getMessage());
        }
    }

    private void applyAddressChange(Order order, Map<String, Object> payload) {
        if (payload == null) return;
        boolean changed = false;
        Object addr = payload.get("address");
        Object city = payload.get("city");
        if (addr instanceof String s && !s.isBlank()) { order.setDropoffAddress(s); changed = true; }
        if (city instanceof String s && !s.isBlank()) { order.setDropoffCity(s); changed = true; }
        if (changed) {
            // Address moved → drop the stale pin and re-geocode (async, after commit).
            order.setDropoffLat(null);
            order.setDropoffLng(null);
            orderRepo.save(order);
            orderGeocodingService.enrichOrderAsync(order.getId());
            log.info("ERP inbound ADDRESS applied — orderId={} (re-geocoding)", order.getId());
        }
    }

    private void applyDateChange(Order order, Map<String, Object> payload) {
        if (payload == null) return;
        Object dateRaw = payload.get("scheduledAt");
        // Guard against Odoo's empty-field sentinel ("false") and any value too short to be a datetime
        // ("YYYY-MM-DD HH:MM" needs ≥16 chars) — both would otherwise throw on the substring below.
        if (!(dateRaw instanceof String s) || s.isBlank() || "false".equalsIgnoreCase(s) || s.length() < 16) return;
        try {
            order.setRescheduledAt(LocalDateTime.parse(s.replace(' ', 'T').substring(0, 16)));
            orderRepo.save(order);
            log.info("ERP inbound DATE applied — orderId={} newDate={}", order.getId(), s);
        } catch (Exception e) {
            log.warn("ERP inbound DATE: could not parse '{}' for orderId={}: {}", s, order.getId(), e.getMessage());
        }
    }

    /** Departed = the parcel has left the depot; ASM is then the source of truth. */
    private boolean hasDeparted(DeliveryStatus status) {
        return status == DeliveryStatus.PICKED_UP
                || status == DeliveryStatus.IN_TRANSIT
                || status == DeliveryStatus.DELIVERED
                || status == DeliveryStatus.PARTIALLY_DELIVERED;
    }

    private Order findOrder(String erpOrderId) {
        if (erpOrderId == null || erpOrderId.isBlank()) return null;
        return orderRepo.findByErpExternalRef(erpOrderId)
                .or(() -> orderRepo.findByErpOrderId(erpOrderId))
                .orElse(null);
    }

    /** Advance the anti-replay cursor; never enqueues an ASM→Odoo sync (anti-loop). */
    private void touchSynced(Order order, LocalDateTime odooWriteDate) {
        order.setLastSyncedAt(odooWriteDate != null ? odooWriteDate : LocalDateTime.now());
        orderRepo.save(order);
    }
}
