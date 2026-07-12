package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single client-uploaded evidence photo attached to a return (RMA). Populated by the public
 * self-service return flow on the tracking page; surfaced to staff in the admin return drawer.
 */
@Entity
@Table(name = "rma_photo", indexes = {
        @Index(name = "idx_rma_photo_rma", columnList = "rma_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RmaPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "rma_id", nullable = false)
    private UUID rmaId;

    @Column(name = "url", nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
