package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * First-class aggregate for a driver-to-driver custody transfer. Replaces the
 * scattered {@code handoff_*} booleans previously kept on {@link RouteStop} and
 * carries the full lifecycle, a hardened one-time code, SLA timestamps, the
 * acting parties, and optional confirmation evidence (GPS + photo).
 */
@Entity
@Table(name = "handoffs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Handoff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Column(name = "route_id")
    private UUID routeId;

    @Column(name = "route_stop_id")
    private UUID routeStopId;

    @Column(name = "from_driver_id", nullable = false)
    private UUID fromDriverId;

    @Column(name = "to_driver_id", nullable = false)
    private UUID toDriverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    @Builder.Default
    private HandoffState state = HandoffState.REQUESTED;

    // ── One-time code (digital handshake) ────────────────────────────────────
    @Column(name = "token", length = 16)
    private String token;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "token_attempts", nullable = false)
    @Builder.Default
    private Integer tokenAttempts = 0;

    // ── Lifecycle timeline + actors ──────────────────────────────────────────
    @Column(name = "requested_at", nullable = false)
    @Builder.Default
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(name = "requested_by", length = 100)
    private String requestedBy;

    @Column(name = "in_progress_at")
    private LocalDateTime inProgressAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    /** True once an overdue alert has been pushed, so the SLA sweeper fires once. */
    @Column(name = "overdue_notified_at")
    private LocalDateTime overdueNotifiedAt;

    @Column(name = "reason", length = 500)
    private String reason;

    // ── Confirmation evidence ────────────────────────────────────────────────
    @Column(name = "confirm_lat", precision = 10, scale = 7)
    private BigDecimal confirmLat;

    @Column(name = "confirm_lng", precision = 10, scale = 7)
    private BigDecimal confirmLng;

    @Column(name = "evidence_url", length = 500)
    private String evidenceUrl;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
