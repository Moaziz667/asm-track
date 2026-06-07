package com.asm.delivery.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "rma_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RmaItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rma_id", nullable = false)
    @JsonIgnore
    private Rma rma;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "name", length = 255)
    private String name;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", precision = 12, scale = 3)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", length = 20)
    @Builder.Default
    private RmaItemCondition condition = RmaItemCondition.RESELLABLE;

    @Column(name = "reason", length = 255)
    private String reason;
}
