package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "proof_of_delivery")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProofOfDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, unique = true)
    private UUID deliveryId;

    @Column(name = "signature_url", length = 500)
    private String signatureUrl;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    @Column(name = "collected_at", nullable = false)
    @Builder.Default
    private LocalDateTime collectedAt = LocalDateTime.now();

    @Column(name = "lat", precision = 10, scale = 7)
    private BigDecimal lat;

    @Column(name = "lng", precision = 10, scale = 7)
    private BigDecimal lng;
}
