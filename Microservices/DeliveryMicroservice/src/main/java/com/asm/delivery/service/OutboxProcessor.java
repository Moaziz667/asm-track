package com.asm.delivery.service;

import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.OutboxEvent;
import com.asm.delivery.erp.ErpSyncService;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final DeliveryRepository deliveryRepo;

    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        List<OutboxEvent> events = outboxRepo.findByStatusOrderByCreatedAtAsc("PENDING");
        if (events.isEmpty()) return;

        log.info("Processing {} outbox events", events.size());
        for (OutboxEvent event : events) {
            try {
                handleEvent(event);
                event.setStatus("PROCESSED");
                event.setProcessedAt(LocalDateTime.now());
            } catch (Exception e) {
                log.error("Failed to process outbox event {}: {}", event.getId(), e.getMessage());
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(e.getMessage());
                if (event.getRetryCount() > 5) {
                    event.setStatus("FAILED");
                }
            }
            outboxRepo.save(event);
        }
    }

    private void handleEvent(OutboxEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), Map.class);
        
        switch (event.getEventType()) {
            case "ERP_SYNC_STOCK":
                UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
                Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                        .orElseThrow(() -> new Exception("Delivery not found for outbox sync: " + deliveryId));
                if (delivery.getOrder() == null) {
                    log.warn("Skipping ERP sync for delivery {}: No order associated", deliveryId);
                } else {
                    String syncStatus = delivery.getOrder().getOdooSyncStatus();
                    if ("SYNCED".equals(syncStatus)) {
                        log.info("Skipping ERP sync for delivery {}: already SYNCED", deliveryId);
                    } else if ("PENDING_RETRY".equals(syncStatus) || "SYNC_FAILED".equals(syncStatus)) {
                        log.info("Skipping ERP sync for delivery {}: status={}, retry scheduler handles it", deliveryId, syncStatus);
                    } else {
                        erpSyncService.syncStockUpdate(delivery.getOrder());
                    }
                }
                break;
            case "FCM_NOTIFICATION":
                // Logic to call FCM service based on payload
                break;
            default:
                log.warn("Unknown event type: {}", event.getEventType());
        }
    }

    @Transactional
    public void enqueue(String type, Object payload) {
        try {
            OutboxEvent event = OutboxEvent.builder()
                    .eventType(type)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status("PENDING")
                    .build();
            outboxRepo.save(event);
        } catch (Exception e) {
            log.error("Failed to enqueue outbox event", e);
        }
    }
}
