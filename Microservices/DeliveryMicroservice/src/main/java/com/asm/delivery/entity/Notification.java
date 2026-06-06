package com.asm.delivery.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * A persisted admin notification (ERP sync failure, SLA breach, backorder created, …). Live
 * delivery is over STOMP; this row makes it durable, queryable, and shared across admins.
 */
@Entity
@Table(name = "notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    @Column(name = "title", length = 200)
    private String title;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "order_ref", length = 100)
    private String orderRef;

    @Column(name = "delivery_id", length = 64)
    private String deliveryId;

    @Column(name = "route_id", length = 64)
    private String routeId;

    @Column(name = "driver_id", length = 64)
    private String driverId;

    @Column(name = "driver_name", length = 150)
    private String driverName;

    @Column(name = "client_name", length = 200)
    private String clientName;

    @Type(JsonType.class)
    @Column(name = "payload", columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean read = false;

    @Column(name = "acknowledged", nullable = false)
    @Builder.Default
    private boolean acknowledged = false;

    @Column(name = "acknowledged_by", length = 150)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
