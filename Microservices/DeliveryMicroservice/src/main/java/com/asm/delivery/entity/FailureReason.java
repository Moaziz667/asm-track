package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Configurable delivery-failure reason (referential).
 *
 * <p>Replaces the frozen {@link FailureCode} enum at the UI layer while keeping that enum as the stable
 * analytics <b>category</b>: each configured reason maps to one category so existing KPI/grouping logic
 * (OpsAnalytics, PDF reports) keeps working unchanged. <b>Applicability</b> (where a motif is offered —
 * full failure vs per-item) is controlled separately by {@link #appliesTo}, so analytics and placement
 * stay decoupled. Single-tenant: no company scoping.
 */
@Entity
@Table(name = "failure_reasons",
        uniqueConstraints = @UniqueConstraint(name = "uq_failure_reasons_code",
                columnNames = {"code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FailureReason {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Stable machine code, globally unique (e.g. CLIENT_ABSENT, PORTE_FERMEE). */
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    /** Human label shown to dispatchers and drivers. */
    @Column(name = "label", nullable = false, length = 160)
    private String label;

    /** Analytics category — maps the configurable reason onto the stable enum. */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private FailureCode category;

    /** Where this motif is offered (full failure / per-item refused/damaged/missing). */
    @Convert(converter = FailureContextSetConverter.class)
    @Column(name = "applies_to", nullable = false, length = 120)
    @Builder.Default
    private Set<FailureContext> appliesTo = EnumSet.of(FailureContext.FAILURE);

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
