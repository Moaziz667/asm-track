package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String actorName;

    @Column(nullable = false)
    private String actorRole;

    @Column(nullable = false)
    private String action; // e.g., "DELETE_ROUTE", "UPDATE_SLA", "FORCE_REASSIGN"

    @Column(columnDefinition = "TEXT")
    private String resourceId; // ID of the route, delivery, etc.

    @Column(columnDefinition = "TEXT")
    private String details; // JSON or text details of the change

    @Column(nullable = false)
    private String ipAddress;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
