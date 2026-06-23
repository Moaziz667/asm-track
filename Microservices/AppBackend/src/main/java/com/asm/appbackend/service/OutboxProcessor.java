package com.asm.appbackend.service;

import com.asm.appbackend.entity.OutboxEvent;
import com.asm.appbackend.repository.OutboxRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains the AppBackend IAM outbox → Keycloak (via {@link IamCommandApplier}), exactly-once with
 * retry/backoff. AppBackend owns Keycloak, so admin async ops (role/name/email/enable) are applied
 * directly here rather than round-tripping the broker. Mirrors the DeliveryMicroservice outbox.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;
    private final IamCommandApplier iamApplier;

    @Scheduled(fixedDelay = 15000)
    public void processOutbox() {
        int recovered = outboxRepo.recoverStuckEvents(LocalDateTime.now().minusMinutes(5));
        if (recovered > 0) log.warn("Recovered {} stuck PROCESSING outbox events", recovered);

        List<OutboxEvent> events = claimEvents();
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                handleEvent(event);
                markProcessed(event.getId());
            } catch (Exception e) {
                log.error("IAM outbox event failed — eventId={} type={} retry={} reason={}",
                        event.getId(), event.getEventType(), event.getRetryCount(), e.getMessage(), e);
                handleFailure(event.getId(), e.getMessage());
            }
        }
    }

    @Transactional
    public List<OutboxEvent> claimEvents() {
        List<OutboxEvent> events = outboxRepo.findPendingWithLock("PENDING", LocalDateTime.now(), 20);
        for (OutboxEvent event : events) event.setStatus("PROCESSING");
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
        });
    }

    private void handleEvent(OutboxEvent event) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
        iamApplier.apply(event.getEventType(), payload);
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
