package com.asm.driver.service;

import com.asm.driver.config.RabbitMQConfig;
import com.asm.driver.entity.OutboxEvent;
import com.asm.driver.repository.OutboxRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains the driver IAM outbox by publishing each command to {@code iam.exchange}; AppBackend (the
 * sole Keycloak owner) consumes and applies it. Same retry/backoff contract as the AppBackend/Delivery
 * outboxes. DriverService no longer talks to Keycloak directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    // IAM op codes — MUST match AppBackend's IamCommandApplier constants.
    public static final String IAM_PROVISION    = "IAM_PROVISION";
    public static final String IAM_UPDATE_EMAIL = "IAM_UPDATE_EMAIL";
    public static final String IAM_UPDATE_NAME  = "IAM_UPDATE_NAME";
    public static final String IAM_SET_ENABLED  = "IAM_SET_ENABLED";
    public static final String IAM_SET_PICTURE  = "IAM_SET_PICTURE";
    public static final String IAM_DELETE       = "IAM_DELETE";
    public static final String IAM_LOGOUT       = "IAM_LOGOUT";

    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final com.asm.driver.config.TenantIterator tenantIterator;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    // @Transactional on methods invoked via `this` never engages the proxy (self-invocation): the
    // SKIP LOCKED claim and the PENDING→PROCESSING flip used to run in separate implicit
    // transactions, so two instances could claim the same event. TransactionTemplate makes the
    // claim genuinely atomic regardless of how the method is reached.
    private org.springframework.transaction.support.TransactionTemplate tx() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    // The IAM outbox lives in each tenant's schema, so drain it once per provisioned tenant — a
    // scheduled thread carries no TenantContext, so an unscoped run would only touch the empty
    // `public` schema and no tenant's driver IAM commands would ever reach AppBackend/Keycloak.
    @Scheduled(fixedDelay = 15000)
    public void processOutbox() {
        tenantIterator.forEachActive(companyId -> processOutboxForTenant());
    }

    private void processOutboxForTenant() {
        int recovered = outboxRepo.recoverStuckEvents(LocalDateTime.now().minusMinutes(5));
        if (recovered > 0) log.warn("Recovered {} stuck PROCESSING outbox events", recovered);

        List<OutboxEvent> events = claimEvents();
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                handleEvent(event);
                markProcessed(event.getId());
            } catch (Exception e) {
                log.error("IAM outbox publish failed — eventId={} type={} retry={} reason={}",
                        event.getId(), event.getEventType(), event.getRetryCount(), e.getMessage(), e);
                handleFailure(event.getId(), e.getMessage());
            }
        }
    }

    public List<OutboxEvent> claimEvents() {
        return tx().execute(status -> {
            List<OutboxEvent> events = outboxRepo.findPendingWithLock("PENDING", LocalDateTime.now(), 20);
            for (OutboxEvent event : events) event.setStatus("PROCESSING");
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
            int retry = event.getRetryCount() + 1;
            event.setRetryCount(retry);
            event.setLastError(error);
            if (retry <= 15) {
                event.setStatus("PENDING");
                long backoff = (long) Math.min(Math.pow(2, retry) * 5, 3600 * 4);
                event.setNextRetryAt(LocalDateTime.now().plusSeconds(backoff));
            } else {
                event.setStatus("FAILED");
                log.error("IAM outbox event dead-lettered — eventId={} type={} lastError={}",
                        eventId, event.getEventType(), error);
            }
            outboxRepo.save(event);
        }));
    }

    private void handleEvent(OutboxEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
        payload.put("op", event.getEventType());
        rabbitTemplate.convertAndSend(RabbitMQConfig.IAM_EXCHANGE, RabbitMQConfig.IAM_ROUTING_KEY, payload);
    }

    /** Enqueue an IAM command in the caller's transaction (must run inside one). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String op, Map<String, Object> payload) {
        try {
            outboxRepo.save(OutboxEvent.builder()
                    .eventType(op)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status("PENDING")
                    .retryCount(0)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to enqueue IAM outbox event", e);
        }
    }
}
