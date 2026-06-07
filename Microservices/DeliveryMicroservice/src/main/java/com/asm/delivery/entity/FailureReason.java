package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Configurable delivery-failure reason (referential).
 *
 * <p>Replaces the frozen {@link FailureCode} enum at the UI layer while keeping
 * that enum as the stable analytics <b>category</b>: each configured reason maps
 * to one category so existing KPI/grouping logic (OpsAnalytics, PDF reports)
 * keeps working unchanged. Scoped per company for the per-instance arch.
 */
@Entity
@Table(name = "failure_reasons",
        uniqueConstraints = @UniqueConstraint(name = "uk_failure_reason_company_code",
                columnNames = {"company_id", "code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FailureReason {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** Stable machine code, unique per company (e.g. CLIENT_ABSENT, PORTE_FERMEE). */
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    /** Human label shown to dispatchers and drivers. */
    @Column(name = "label", nullable = false, length = 160)
    private String label;

    /** Analytics category — maps the configurable reason onto the stable enum. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private FailureCode category;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
