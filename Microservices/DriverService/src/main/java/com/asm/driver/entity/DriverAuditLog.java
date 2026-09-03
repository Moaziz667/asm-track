package com.asm.driver.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "driver_audit_logs",
    indexes = {
        @Index(name = "idx_driver_audit_resource", columnList = "resource_id, created_at DESC"),
        @Index(name = "idx_driver_audit_action", columnList = "action"),
        @Index(name = "idx_driver_audit_created", columnList = "created_at DESC")
    }
)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class DriverAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_name", length = 150)
    private String actorName;

    @Column(name = "actor_role", length = 50)
    private String actorRole;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * When this row reached the shared audit trail, null while it has not.
     *
     * <p>The row is written here first and published after: the local write is transactional, the
     * broker call is not. Null therefore means "owed to the trail", whether the broker was down, the
     * service died between the two, or the row predates the move to the shared trail entirely. The
     * startup backfill drains them, so a restart is all the recovery this needs.
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
