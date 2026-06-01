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
}
