package com.asm.appbackend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Transactional outbox for IAM provisioning commands. Written in the same DB transaction as the
 * admin-user mutation, then drained by {@code OutboxProcessor} → Keycloak (exactly-once, retried).
 * Mirrors the proven DeliveryMicroservice outbox.
 */
@Entity
@Table(name = "outbox_event")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "retry_count")
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (nextRetryAt == null) {
            nextRetryAt = createdAt;
        }
    }
}
