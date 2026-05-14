package com.asm.delivery.service;

import com.asm.delivery.dto.request.PartialDeliveryItem;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.OutboxEvent;
import com.asm.delivery.erp.ErpSyncService;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.OutboxRepository;
import com.asm.delivery.transport.TransportPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxProcessor {

    private final OutboxRepository outboxRepo;
    private final ErpSyncService erpSyncService;
    private final ObjectMapper objectMapper;
    private final DeliveryRepository deliveryRepo;
    private final TransportPort transportPort;

    @Scheduled(fixedDelay = 20000)
    public void processOutbox() {
        // Step 1: Claim events (with SKIP LOCKED to prevent concurrent instance races)
        List<OutboxEvent> events = claimEvents();
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                // Step 2: Handle actual execution outside the 'claim' lock
                handleEvent(event);
                markProcessed(event.getId());
            } catch (Exception e) {
                log.error("Outbox execution failed for event {}: {}", event.getId(), e.getMessage());
                handleFailure(event.getId(), e.getMessage());
            }
        }
    }

    /** Atomically claims a batch of events to prevent multiple workers from picking them up. */
    @Transactional
    public List<OutboxEvent> claimEvents() {
        List<OutboxEvent> events = outboxRepo.findPendingWithLock("PENDING", 10);
        for (OutboxEvent event : events) {
            event.setStatus("PROCESSING");
        }
        return outboxRepo.saveAll(events);
    }

    @Transactional
    public void markProcessed(UUID eventId) {
        outboxRepo.findById(eventId).ifPresent(event -> {
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            outboxRepo.save(event);
        });
    }

    @Transactional
    public void handleFailure(UUID eventId, String error) {
        outboxRepo.findById(eventId).ifPresent(event -> {
            int newRetryCount = event.getRetryCount() + 1;
            event.setRetryCount(newRetryCount);
            event.setLastError(error);
            // If we haven't exceeded max retries, put it back to PENDING.
            // Using 50 retries at 20s each gives us ~16 minutes of tolerance for downtime.
            if (newRetryCount <= 50) {
                event.setStatus("PENDING");
            } else {
                event.setStatus("FAILED");
                log.error("Event {} permanently FAILED after 50 retries.", eventId);
            }
            outboxRepo.save(event);
        });
    }

    private void handleEvent(OutboxEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
        String transactionId = event.getId().toString();
        
        switch (event.getEventType()) {
            case "ERP_SYNC_STOCK":
                processErpSync(payload, transactionId);
                break;
            case "ERP_SYNC_FAILURE":
                processErpFailure(payload, transactionId);
                break;
            case "ERP_SYNC_CANCELLATION":
                processErpCancellation(payload, transactionId);
                break;
            case "INCREMENT_DRIVER_STAT":
                String driverId = (String) payload.get("driverId");
                String stat = (String) payload.get("stat");
                transportPort.incrementStat(driverId, stat);
                break;
            case "UPDATE_DRIVER_LOCATION":
                String locDriverId = (String) payload.get("driverId");
                Number lat = (Number) payload.get("lat");
                Number lng = (Number) payload.get("lng");
                transportPort.updateLocation(locDriverId, lat.doubleValue(), lng.doubleValue());
                break;
            default:
                log.warn("Unknown outbox event type: {}", event.getEventType());
        }
    }

    private void processErpCancellation(Map<String, Object> payload, String txId) throws Exception {
        UUID orderId = UUID.fromString((String) payload.get("orderId"));
        // MUST use join-fetch variant: Order fields (erpOrderId, etc.) are accessed
        // by ErpSyncService outside a Hibernate session → LazyInitializationException otherwise.
        Delivery delivery = deliveryRepo.findByOrderIdWithOrder(orderId).orElse(null);
        if (delivery == null || delivery.getOrder() == null) {
            log.warn("ERP_SYNC_CANCELLATION: no delivery/order found for orderId={}, skipping", orderId);
            return;
        }
        erpSyncService.syncOrderCancellation(delivery.getOrder(), txId);
    }

    private void processErpSync(Map<String, Object> payload, String txId) throws Exception {
        UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> new Exception("Delivery not found: " + deliveryId));
        
        if (delivery.getOrder() == null) return;
        
        // Guard: skip only if a previous successful sync already moved it to SYNCED.
        // If it is still the default "SYNCED" (never set to PENDING_SYNC before enqueue),
        // that means the enqueue site forgot to reset the status — warn and proceed anyway.
        String syncStatus = delivery.getOrder().getOdooSyncStatus();
        if ("SYNCED".equals(syncStatus)) {
            log.warn("processErpSync: order {} is SYNCED — was odooSyncStatus reset to PENDING_SYNC before enqueue? Proceeding anyway to ensure Odoo consistency.", delivery.getOrder().getId());
            // Do NOT return here — fall through and let ErpSyncService decide
        }

        Boolean isPartial = (Boolean) payload.get("isPartial");
        if (Boolean.TRUE.equals(isPartial)) {
            List<PartialDeliveryItem> items = objectMapper.convertValue(
                payload.get("partialItems"), new TypeReference<List<PartialDeliveryItem>>() {});
            erpSyncService.syncPartialStockUpdate(delivery.getOrder(), items, txId);
        } else {
            erpSyncService.syncStockUpdate(delivery.getOrder(), txId);
        }
    }

    private void processErpFailure(Map<String, Object> payload, String txId) throws Exception {
        UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> new Exception("Delivery not found: " + deliveryId));
        
        if (delivery.getOrder() == null) return;

        String code = (String) payload.get("failureCode");
        String comment = (String) payload.get("comment");
        erpSyncService.syncFailure(delivery.getOrder(), code, comment, txId);
    }

    @Transactional
    public void enqueue(String type, Object payload) {
        try {
            OutboxEvent event = OutboxEvent.builder()
                    .eventType(type)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status("PENDING")
                    .retryCount(0)
                    .build();
            outboxRepo.save(event);
        } catch (Exception e) {
            log.error("Failed to enqueue outbox event", e);
        }
    }
}
