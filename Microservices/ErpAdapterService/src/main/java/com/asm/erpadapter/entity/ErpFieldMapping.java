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
 * Where, in one customer's ERP, a piece of business data actually lives.
 *
 * <p>Sibling of {@link ErpMapping}, deliberately a separate table despite the similar shape, because
 * the two answer different questions and carry different risk. {@code ErpMapping} overrides a
 * <em>capability</em> — which method validates a transfer — and getting it wrong breaks stock. This
 * one overrides where a <em>value</em> is read from, and getting it wrong shows a wrong label. Mixing
 * them would put both in the same screen, where an integrator relabelling a customer reference could
 * silently corrupt quantities.
 *
 * <p>A row exists only when the customer differs from the shipped default: absence means "use the
 * default", so an untouched tenant behaves exactly as before mapping existed.
 *
 * <p>Example — the customer keeps the recipient's name on a custom field rather than on the partner:
 * <pre>
 * tenant_id:      550e8400-e29b-41d4-a716-446655440000
 * provider:       odoo
 * canonical_field: CUSTOMER_NAME
 * source_path:    x_client_nom
 * </pre>
 *
 * <p>{@code sourcePath} is a dotted path so a value can be read through a relation
 * ({@code partner_id.name}, {@code warehouse_id.partner_id.city}) — most customer data hangs off a
 * related record rather than the document itself, so a plain field name would not reach it.
 */
@Entity
@Table(name = "erp_field_mapping", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"tenant_id", "canonical_field", "custom_key"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpFieldMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID tenantId;

    /** Lowercase provider key ({@code odoo}, {@code erpnext}) — a path is only valid for its own ERP. */
    @Column(nullable = false, length = 32)
    private String provider;

    /**
     * The {@link com.asm.erpadapter.mapping.CanonicalField} being overridden, or {@code null} for a
     * customer-defined extra field (which is identified by {@link #customKey} instead).
     */
    @Column(length = 64)
    private String canonicalField;

    /**
     * Label for an extra field that has no canonical equivalent; also its key in the order's
     * {@code custom_fields} bag. Null for a canonical override.
     */
    @Column(length = 64)
    private String customKey;

    /** Dotted path in the ERP, e.g. {@code partner_id.name}. */
    @Column(nullable = false, length = 256)
    private String sourcePath;

    /**
     * How to read the raw ERP value — see {@code SourceKind}. Odoo relations come back as
     * {@code [id, "label"]}, so "take the label" and "take the id" have to be distinguishable.
     */
    @Column(nullable = false, length = 24)
    private String readAs;

    /**
     * The normalised ERP type of the target field, as observed when this mapping was saved.
     *
     * <p>The baseline for drift detection: an integrator maps a {@code selection}, the customer later
     * turns it into a free-text field, and the mapping keeps resolving while silently meaning
     * something else. Comparing what the catalogue reports today against what was recorded here is
     * what turns that into a reportable event instead of values quietly going blank.
     *
     * <p>Null for rows written before this existed — absence means "no baseline", never "unchanged".
     */
    @Column(length = 24)
    private String sourceType;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Who set it, for support: an integrator needs to know who changed a mapping and when. */
    @Column(length = 128)
    private String updatedBy;

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (readAs == null) readAs = "AUTO";
    }

    @jakarta.persistence.PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
