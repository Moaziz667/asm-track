package com.asm.erpadapter.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Customer-specific mapping override for ERP capabilities.
 *
 * <p>When a customer has a custom field or method that differs from the default
 * Odoo capability registry, this table stores the override. The {@link com.asm.erpadapter.adapter.odoo.CapabilityResolver}
 * checks this table before falling back to the default registry.
 *
 * <p>Example: Customer ABC has a custom field {@code x_delivery_zone} for the
 * {@code DONE_QUANTITY} capability. This row maps it:
 * <pre>
 * tenant_id:     550e8400-e29b-41d4-a716-446655440000
 * capability:    DONE_QUANTITY
 * mapping_type:  FIELD
 * odoo_name:     x_delivery_zone
 * target_model:  stock.move.line
 * </pre>
 */
@Entity
@Table(name = "erp_mapping", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id", "capability"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 64)
    private String capability;

    @Column(nullable = false, length = 16)
    private String mappingType; // FIELD or METHOD

    @Column(nullable = false, length = 128)
    private String odooName; // the Odoo field or method name

    @Column(nullable = false, length = 128)
    private String targetModel; // the Odoo model

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
