package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "depots")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Depot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    /** ERP warehouse code this depot mirrors (the stable sync/resolution key). */
    @Column(name = "warehouse_code", length = 50)
    private String warehouseCode;

    /** ERP warehouse identifier (e.g. Odoo stock.warehouse id). Informational. */
    @Column(name = "erp_warehouse_id", length = 50)
    private String erpWarehouseId;

    /** ERP provider that owns this depot (e.g. "odoo"). Informational. */
    @Column(name = "provider", length = 20)
    private String provider;

    /** Nullable until coordinates are read from Odoo or geocoded from the address. */
    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
