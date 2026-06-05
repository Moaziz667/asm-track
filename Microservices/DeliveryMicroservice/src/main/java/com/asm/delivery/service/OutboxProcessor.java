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
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class OutboxProcessor {

    private final OutboxRepository outboxRepo;
    private final ErpSyncService erpSyncService;
    private final ObjectMapper objectMapper;
    private final DeliveryRepository deliveryRepo;
    private final TransportPort transportPort;
    private final EventPublisher eventPublisher;
    private final com.asm.delivery.repository.OrderRepository orderRepo;

    public OutboxProcessor(OutboxRepository outboxRepo, ErpSyncService erpSyncService,
                           ObjectMapper objectMapper, DeliveryRepository deliveryRepo,
                           TransportPort transportPort, EventPublisher eventPublisher,
                           com.asm.delivery.repository.OrderRepository orderRepo) {
        this.outboxRepo = outboxRepo;
        this.erpSyncService = erpSyncService;
        this.objectMapper = objectMapper;
        this.deliveryRepo = deliveryRepo;
        this.transportPort = transportPort;
        this.eventPublisher = eventPublisher;
        this.orderRepo = orderRepo;
    }

    @Scheduled(fixedDelay = 20000)
    public void processOutbox() {
        // Recover any events stuck in PROCESSING due to a previous container crash
        int recovered = outboxRepo.recoverStuckEvents(LocalDateTime.now().minusMinutes(5));
        if (recovered > 0) log.warn("Recovered {} stuck PROCESSING events", recovered);

        // Step 1: Claim events (with SKIP LOCKED to prevent concurrent instance races)
        List<OutboxEvent> events = claimEvents();
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                handleEvent(event);
                markProcessed(event.getId());
            } catch (Exception e) {
                log.error("Outbox event failed — eventId={} eventType={} retryCount={} errorClass={} reason={}",
                        event.getId(), event.getEventType(), event.getRetryCount(),
                        e.getClass().getSimpleName(), e.getMessage(), e);
                handleFailure(event.getId(), e.getMessage());
            }
        }
    }

    /** Atomically claims a batch of events to prevent multiple workers from picking them up. */
    @Transactional
    public List<OutboxEvent> claimEvents() {
        List<OutboxEvent> events = outboxRepo.findPendingWithLock("PENDING", LocalDateTime.now(), 10);
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
            // 15 retries with exponential backoff spreads retries over 45+ hours, fully protecting against weekend outages.
            if (newRetryCount <= 15) {
                event.setStatus("PENDING");
                // Exponential backoff: 2^retryCount * 5 seconds, capped at 4 hours per retry attempt
                long backoffSeconds = (long) Math.min(Math.pow(2, newRetryCount) * 5, 3600 * 4);
                event.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds));
            } else {
                event.setStatus("FAILED");
                log.error("Outbox event dead — eventId={} eventType={} retryCount={} lastError={} action=permanent_failure",
                        eventId, event.getEventType(), newRetryCount, error);
                
                // Update Order status in DB to record permanent sync failure
                try {
                    Map<String, Object> payload = objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
                    UUID orderId = null;
                    if (payload.get("orderId") != null) {
                        orderId = UUID.fromString((String) payload.get("orderId"));
                    } else if (payload.get("deliveryId") != null) {
                        UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
                        orderId = deliveryRepo.findByIdWithOrder(deliveryId)
                                .map(d -> d.getOrder() != null ? d.getOrder().getId() : null)
                                .orElse(null);
                    }
                    if (orderId != null) {
                        final UUID resolvedOrderId = orderId;
                        orderRepo.findById(resolvedOrderId).ifPresent(order -> {
                            order.setOdooSyncStatus("SYNC_FAILED");
                            orderRepo.save(order);
                            log.info("Successfully marked order ID={} as SYNC_FAILED after outbox exhaustion", resolvedOrderId);
                        });
                    }
                } catch (Exception ex) {
                    log.error("Failed to mark order sync status as SYNC_FAILED for eventId={}: {}", eventId, ex.getMessage());
                }

                notifyErpSyncFailed(event);
            }
            outboxRepo.save(event);
        });
    }

    /**
     * On a permanently dead-lettered ERP sync, surface an admin notification so a
     * dispatcher can intervene (the dead-letter webhook is ops-only). Resolves the
     * order from the event payload; best-effort — never blocks failure handling.
     */
    private void notifyErpSyncFailed(OutboxEvent event) {
        final String type = event.getEventType();
        if (type == null || !type.startsWith("ERP_")) return;
        try {
            Map<String, Object> payload = objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
            Delivery delivery = null;
            if (payload.get("deliveryId") != null) {
                delivery = deliveryRepo.findByIdWithOrder(UUID.fromString((String) payload.get("deliveryId"))).orElse(null);
            } else if (payload.get("orderId") != null) {
                delivery = deliveryRepo.findByOrderIdWithOrder(UUID.fromString((String) payload.get("orderId"))).orElse(null);
            }
            if (delivery != null && delivery.getOrder() != null) {
                eventPublisher.publishErpSyncFailed(delivery.getOrder(), delivery.getId(), erpOperationCode(type));
            }
        } catch (Exception ex) {
            log.warn("Could not publish erp.sync_failed admin notification for eventId={}: {}", event.getId(), ex.getMessage());
        }
    }

    private String erpOperationCode(String eventType) {
        switch (eventType) {
            case "ERP_SYNC_STOCK":        return "STOCK";
            case "ERP_SYNC_FAILURE":      return "FAILURE_REPORT";
            case "ERP_SYNC_CANCELLATION": return "CANCELLATION";
            default:                      return "SYNC";
        }
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

    @Transactional(propagation = Propagation.MANDATORY)
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
            throw new RuntimeException("Failed to enqueue outbox event", e);
        }
    }
}
