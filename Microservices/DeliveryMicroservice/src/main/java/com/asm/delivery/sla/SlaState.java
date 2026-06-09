package com.asm.delivery.sla;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The single source of truth for a delivery's SLA. One row per delivery, written on every
 * lifecycle transition and by the reconciliation tick. Every screen, PDF and notification
 * reads {@code (phase, health, dueAt)} from here instead of recomputing lateness locally.
 *
 * <p>{@code lastAlertedHealth} provides persisted dedup (replacing the old in-memory Set), so a
 * service restart never re-floods the same breach.
 */
@Entity
@Table(name = "sla_state", uniqueConstraints = {
        @UniqueConstraint(name = "sla_state_delivery_id_key", columnNames = {"delivery_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SlaState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false, length = 20)
    private SlaPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(name = "health", nullable = false, length = 12)
    private SlaHealth health;

    /** The single deadline for the current phase (window-anchored or EOD-Tunis for planning). */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "at_risk_at")
    private LocalDateTime atRiskAt;

    @Column(name = "breached_at")
    private LocalDateTime breachedAt;

    /** Minutes late vs {@code dueAt} once resolvable (positive = retard, null while pending). */
    @Column(name = "late_minutes")
    private Integer lateMinutes;

    /** True when the current lateness is the driver's to own (excludes planning + depot wait). */
    @Column(name = "attributable_to_driver")
    @Builder.Default
    private boolean attributableToDriver = false;

    /** i18n key + params describing the current situation (rendered by the frontend, never English text). */
    @Column(name = "reason_key", length = 60)
    private String reasonKey;

    @Type(JsonType.class)
    @Column(name = "reason_params", columnDefinition = "jsonb")
    private Map<String, String> reasonParams;

    /** Worst health each lifecycle phase ever reached, e.g. {"DEPARTURE":"BREACHED"} — lets the
     *  timeline colour passed phases truthfully instead of a flat green "done". */
    @Type(JsonType.class)
    @Column(name = "phase_health", columnDefinition = "jsonb")
    private Map<String, String> phaseHealth;

    /** Last health an {@code sla.alert} was emitted for — persisted dedup across restarts. */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_alerted_health", length = 12)
    private SlaHealth lastAlertedHealth;

    /** When the phase or health last changed (drives "since" copy and alert transitions). */
    @Column(name = "last_transition_at")
    private LocalDateTime lastTransitionAt;

    /** Grace window after a re-plan/stop-removal during which the planning alarm is suppressed. */
    @Column(name = "suppress_alerts_until")
    private LocalDateTime suppressAlertsUntil;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
