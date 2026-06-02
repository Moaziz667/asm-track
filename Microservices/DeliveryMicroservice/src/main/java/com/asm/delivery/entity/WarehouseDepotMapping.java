package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Maps an ERP warehouse (e.g. Odoo "SFAX") to an ASM depot, so an imported
 * delivery note's source warehouse resolves to a concrete source depot.
 * Keyed by warehouse code (one ERP per deployment); {@code provider} is kept
 * informational for future multi-ERP disambiguation.
 */
@Entity
@Table(name = "warehouse_depot_mappings")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class WarehouseDepotMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "warehouse_code", nullable = false, unique = true, length = 50)
    private String warehouseCode;

    @Column(name = "depot_id", nullable = false)
    private UUID depotId;

    /** ERP provider this warehouse code belongs to (e.g. "odoo"); informational. */
    @Column(name = "provider", length = 20)
    private String provider;

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
