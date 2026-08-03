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
    private final com.asm.delivery.repository.RmaRepository rmaRepo;
    private final com.asm.delivery.config.TenantIterator tenantIterator;
    private final com.asm.delivery.storage.MinioStorageService minioStorageService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    // @Transactional on methods invoked via `this` never engages the proxy (self-invocation): the
    // SKIP LOCKED claim and the PENDING→PROCESSING flip used to run in separate implicit
    // transactions, so two instances could claim the same event. TransactionTemplate makes the
    // claim genuinely atomic regardless of how the method is reached.
    private org.springframework.transaction.support.TransactionTemplate tx() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    public OutboxProcessor(OutboxRepository outboxRepo, ErpSyncService erpSyncService,
                           ObjectMapper objectMapper, DeliveryRepository deliveryRepo,
                           TransportPort transportPort, EventPublisher eventPublisher,
                           com.asm.delivery.repository.OrderRepository orderRepo,
                           com.asm.delivery.repository.RmaRepository rmaRepo,
                           com.asm.delivery.config.TenantIterator tenantIterator,
                           com.asm.delivery.storage.MinioStorageService minioStorageService,
                           org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.outboxRepo = outboxRepo;
        this.erpSyncService = erpSyncService;
        this.objectMapper = objectMapper;
        this.deliveryRepo = deliveryRepo;
        this.transportPort = transportPort;
        this.eventPublisher = eventPublisher;
        this.orderRepo = orderRepo;
        this.rmaRepo = rmaRepo;
        this.tenantIterator = tenantIterator;
        this.minioStorageService = minioStorageService;
        this.transactionManager = transactionManager;
    }

    @Scheduled(fixedDelay = 20000)
    public void processOutbox() {
        tenantIterator.forEachActive(companyId -> processOutboxForTenant());
    }

    private void processOutboxForTenant() {
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
    public List<OutboxEvent> claimEvents() {
        return tx().execute(status -> {
            List<OutboxEvent> events = outboxRepo.findPendingWithLock("PENDING", LocalDateTime.now(), 10);
            for (OutboxEvent event : events) {
                event.setStatus("PROCESSING");
            }
            return outboxRepo.saveAll(events);
        });
    }

    public void markProcessed(UUID eventId) {
        tx().executeWithoutResult(status -> outboxRepo.findById(eventId).ifPresent(event -> {
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            outboxRepo.save(event);
        }));
    }

    public void handleFailure(UUID eventId, String error) {
        tx().executeWithoutResult(status -> outboxRepo.findById(eventId).ifPresent(event -> {
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
                
                // A dead-lettered RETURN must mark the RMA itself SYNC_FAILED — otherwise it stays stuck
                // PENDING_SYNC forever (the reverse-move loop only closes via the result message, which
                // never arrives once the command is dead). Best-effort, like the order path below.
                if ("ERP_SYNC_RETURN".equals(event.getEventType())) {
                    try {
                        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
                        Object rmaIdRaw = payload.get("rmaId");
                        if (rmaIdRaw != null) {
                            UUID rmaId = UUID.fromString((String) rmaIdRaw);
                            rmaRepo.findById(rmaId).ifPresent(rma -> {
                                rma.setErpSyncStatus("SYNC_FAILED");
                                rma.setErpSyncError(event.getLastError());
                                rmaRepo.save(rma);
                                log.info("Marked RMA id={} as SYNC_FAILED after outbox exhaustion", rmaId);
                            });
                        }
                    } catch (Exception ex) {
                        log.error("Failed to mark RMA SYNC_FAILED for eventId={}: {}", eventId, ex.getMessage());
                    }
                }

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
                            order.setErpSyncStatus("SYNC_FAILED");
                            order.setLastSyncOp(resyncOpForEventType(event.getEventType()));
                            order.setLastSyncError(event.getLastError());
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
        }));
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
                delivery = deliveryRepo.findAllByOrderIdWithOrder(UUID.fromString((String) payload.get("orderId"))).stream().findFirst().orElse(null);
            }
            if (delivery != null && delivery.getOrder() != null) {
                eventPublisher.publishErpSyncFailed(delivery.getOrder(), delivery.getId(), ErpNotificationLabel.of(type));
            }
        } catch (Exception ex) {
            log.warn("Could not publish erp.sync_failed admin notification for eventId={}: {}", event.getId(), ex.getMessage());
        }
    }

    /** Canonical sync op stored on the order so the operator "Resync" knows what to replay. */
    private String resyncOpForEventType(String eventType) {
        return switch (eventType == null ? "" : eventType) {
            case "ERP_SYNC_STOCK"        -> "STOCK_FULL";
            case "ERP_SYNC_FAILURE"      -> "FAILURE";
            case "ERP_SYNC_CANCELLATION" -> "CANCELLATION";
            case "ERP_SYNC_POD"          -> "POD";
            case "ERP_SYNC_RETURN"       -> "RETURN";
            case "ERP_SYNC_RESCHEDULE"   -> "RESCHEDULE";
            default                      -> "SYNC";
        };
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
            case "ERP_SYNC_POD":
                processErpPod(payload, transactionId);
                break;
            case "ERP_SYNC_RETURN":
                processErpReturn(payload, transactionId);
                break;
            case "ERP_SYNC_RESCHEDULE":
                processErpReschedule(payload, transactionId);
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
        Delivery delivery;
        // B4 — Prefer the exact delivery that was cancelled; an order can have several deliveries and
        // each maps to its own Odoo picking. Fall back to the first delivery only for legacy events
        // enqueued before deliveryId was carried.
        Object deliveryIdRaw = payload.get("deliveryId");
        if (deliveryIdRaw != null) {
            delivery = deliveryRepo.findByIdWithOrder(UUID.fromString((String) deliveryIdRaw)).orElse(null);
        } else {
            delivery = deliveryRepo.findAllByOrderIdWithOrder(orderId).stream().findFirst().orElse(null);
        }
        if (delivery == null || delivery.getOrder() == null) {
            log.warn("ERP_SYNC_CANCELLATION: no delivery/order found for orderId={} deliveryId={}, skipping",
                    orderId, deliveryIdRaw);
            return;
        }
        erpSyncService.syncOrderCancellation(delivery, txId);
    }

    private void processErpSync(Map<String, Object> payload, String txId) throws Exception {
        UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> new Exception("Delivery not found: " + deliveryId));
        
        if (delivery.getOrder() == null) return;

        // B5 — No status guard here. The order is guaranteed to be PENDING_SYNC because
        // enqueueErpStockSync() sets it atomically when the event is created (see below). The
        // processor always forwards the sync; the SYNCED/SYNC_FAILED verdict is owned by the
        // async ERP result, not by a defensive check at processing time.
        Boolean isPartial = (Boolean) payload.get("isPartial");
        if (Boolean.TRUE.equals(isPartial)) {
            List<PartialDeliveryItem> items = objectMapper.convertValue(
                payload.get("partialItems"), new TypeReference<List<PartialDeliveryItem>>() {});
            erpSyncService.syncPartialStockUpdate(delivery, items, txId);
        } else {
            erpSyncService.syncStockUpdate(delivery, txId);
        }
    }

    private void processErpPod(Map<String, Object> payload, String txId) throws Exception {
        UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> new Exception("Delivery not found: " + deliveryId));
        if (delivery.getOrder() == null) return;

        // Forward the POD metadata + MinIO photo URLs carried in the outbox payload (C5). The image
        // bytes are NOT inlined — the ERP adapter fetches them from these URLs and uploads to Odoo,
        // so neither the outbox table nor the RabbitMQ frame carries the (large) base64 payload.
        // Photo URLs are PRESIGNED here (fresh on every retry attempt) so the adapter's fetch keeps
        // working once the POD bucket is made private (minio.pod-bucket-public-read=false).
        Map<String, Object> pod = new java.util.HashMap<>();
        for (String k : List.of("recipientName", "comment", "deliveredAt", "lat", "lng")) {
            if (payload.get(k) != null) pod.put(k, payload.get(k));
        }
        for (String k : List.of("bonLivraisonPhotoUrl", "packagePhotoUrl")) {
            if (payload.get(k) != null) {
                pod.put(k, minioStorageService.presignedGetUrl(
                        String.valueOf(payload.get(k)), java.time.Duration.ofHours(24)));
            }
        }
        erpSyncService.syncProofOfDelivery(delivery, pod, txId);
    }

    @SuppressWarnings("unchecked")
    private void processErpReturn(Map<String, Object> payload, String txId) throws Exception {
        UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> new Exception("Delivery not found: " + deliveryId));
        if (delivery.getOrder() == null) return;

        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.getOrDefault("items", List.of());
        String reason = (String) payload.get("reason");
        // D2 — Carry rmaId so the adapter echoes it back on the result, letting the result consumer
        // close the reverse-move loop on the right RMA.
        String rmaId = (String) payload.get("rmaId");
        erpSyncService.syncReturn(delivery, items, reason, rmaId, txId);
    }

    private void processErpReschedule(Map<String, Object> payload, String txId) throws Exception {
        UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> new Exception("Delivery not found: " + deliveryId));
        if (delivery.getOrder() == null) return;
        String scheduledAt = (String) payload.get("scheduledAt");
        erpSyncService.syncReschedule(delivery, scheduledAt, txId);
    }

    private void processErpFailure(Map<String, Object> payload, String txId) throws Exception {
        UUID deliveryId = UUID.fromString((String) payload.get("deliveryId"));
        Delivery delivery = deliveryRepo.findByIdWithOrder(deliveryId)
                .orElseThrow(() -> new Exception("Delivery not found: " + deliveryId));
        
        if (delivery.getOrder() == null) return;

        String code = (String) payload.get("failureCode");
        String comment = (String) payload.get("comment");
        erpSyncService.syncFailure(delivery, code, comment, txId);
    }

    /**
     * B5 — Enqueue an ERP stock-sync event AND mark the owning order PENDING_SYNC in the same
     * transaction. Centralizing the status reset here removes the old "did the caller remember to
     * set PENDING_SYNC?" convention: any path that needs a stock sync calls this and the invariant
     * (event enqueued ⇒ order pending) holds structurally.
     *
     * @param deliveryId   the shipment whose stock outcome must reach the ERP
     * @param partial      true for a partial delivery (drives Odoo backorder creation)
     * @param partialItems per-line outcome/quantities, required when {@code partial} is true
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueErpStockSync(UUID deliveryId, boolean partial, List<PartialDeliveryItem> partialItems) {
        deliveryRepo.findByIdWithOrder(deliveryId).ifPresent(delivery -> {
            if (delivery.getOrder() != null) {
                delivery.getOrder().setErpSyncStatus("PENDING_SYNC");
                orderRepo.save(delivery.getOrder());
            }
        });
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("deliveryId", deliveryId.toString());
        payload.put("isPartial", partial);
        if (partial && partialItems != null) {
            payload.put("partialItems", partialItems);
        }
        enqueue("ERP_SYNC_STOCK", payload);
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
