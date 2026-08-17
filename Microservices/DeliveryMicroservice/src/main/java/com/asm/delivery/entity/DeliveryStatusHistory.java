package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "delivery_status_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false)
    private UUID deliveryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_role", length = 10)
    private Role changedByRole;

    @Column(name = "event_key", nullable = false, length = 50)
    private String eventKey;

    @Column(name = "event_params", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String eventParams;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PrePersist
    void prePersist() {
        // Only default when the caller did not set it. An offline driver action replayed on
        // reconnection carries its real tap time (via ActionClock in appendHistory); overwriting it
        // here with now() is exactly the "delivered in the van, not in the basement" bug, on the
        // timeline this time — the line the admin reads shows "par <livreur>, <date>".
        if (changedAt == null) {
            changedAt = LocalDateTime.now();
        }
    }
}
