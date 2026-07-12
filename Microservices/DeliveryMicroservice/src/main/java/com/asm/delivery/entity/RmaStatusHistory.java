package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One immutable row per RMA status transition — the real return timeline shown in the admin drawer.
 * Actor is stored denormalised as name/role strings so it accommodates the public CLIENT actor and
 * survives without a read-time join.
 */
@Entity
@Table(name = "rma_status_history", indexes = {
        @Index(name = "idx_rma_history_rma", columnList = "rma_id, created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RmaStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rma_id", nullable = false)
    private UUID rmaId;

    /** Null on creation (no prior status). */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private RmaStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private RmaStatus toStatus;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "acted_by_name", length = 255)
    private String actedByName;

    @Column(name = "acted_by_role", length = 40)
    private String actedByRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
