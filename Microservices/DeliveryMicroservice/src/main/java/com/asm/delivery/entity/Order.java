package com.asm.delivery.entity;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10)
    private OrderSource source;

    @Column(name = "schema_version", nullable = false, length = 10)
    @Builder.Default
    private String schemaVersion = "1.0.0";

    // ── Client ───────────────────────────────────────────────────────────────
    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "client_name", nullable = false, length = 100)
    private String clientName;

    @Column(name = "client_phone", length = 20)
    private String clientPhone;

    @Column(name = "client_email", length = 100)
    private String clientEmail;

    // ── ERP ──────────────────────────────────────────────────────────────────
    @Column(name = "erp_order_id", unique = true, length = 100)
    private String erpOrderId;

    @Column(name = "erp_external_ref", length = 100)
    private String erpExternalRef;

    // ── Origin ────────────────────────────────────────────────────────────────
    @Column(name = "origin_name", length = 100)
    private String originName;

    @Column(name = "origin_address", columnDefinition = "TEXT")
    private String originAddress;

    @Column(name = "origin_city", length = 100)
    private String originCity;

    @Column(name = "origin_postal_code", length = 20)
    private String originPostalCode;

    @Column(name = "origin_country_code", length = 2)
    private String originCountryCode;

    @Column(name = "origin_contact_name", length = 100)
    private String originContactName;

    @Column(name = "origin_contact_phone", length = 20)
    private String originContactPhone;

    @Column(name = "origin_contact_email", length = 100)
    private String originContactEmail;

    // ── Destination ───────────────────────────────────────────────────────────
    @Column(name = "dropoff_address", nullable = false, columnDefinition = "TEXT")
    private String dropoffAddress;

    @Column(name = "dropoff_city", length = 100)
    private String dropoffCity;

    @Column(name = "dropoff_postal_code", length = 20)
    private String dropoffPostalCode;

    @Column(name = "dropoff_country_code", length = 2)
    @Builder.Default
    private String dropoffCountryCode = "TN";

    @Column(name = "dropoff_lat", precision = 10, scale = 7)
    private BigDecimal dropoffLat;

    @Column(name = "dropoff_lng", precision = 10, scale = 7)
    private BigDecimal dropoffLng;

    @Column(name = "delivery_instructions", columnDefinition = "TEXT")
    private String deliveryInstructions;

    // ── Financial ─────────────────────────────────────────────────────────────
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 3)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "TND";

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, length = 10)
    private PaymentType paymentType;

    @Column(name = "amount_to_collect", nullable = false, precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal amountToCollect = BigDecimal.ZERO;

    // ── Planning ──────────────────────────────────────────────────────────────
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    @Builder.Default
    private OrderPriority priority = OrderPriority.NORMAL;

    // ── Items (JSONB) ─────────────────────────────────────────────────────────
    @Type(JsonType.class)
    @Column(name = "items", columnDefinition = "jsonb", nullable = false)
    private List<OrderItem> items;

    @Column(name = "total_quantity", nullable = false)
    @Builder.Default
    private Integer totalQuantity = 0;

    @Column(name = "total_weight_kg", nullable = false, precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal totalWeightKg = BigDecimal.ZERO;

    // ── Status ────────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    // ── Odoo integration ──────────────────────────────────────────────────────
    @Column(name = "client_odoo_partner_id")
    private Integer clientOdooPartnerId;

    @Column(name = "odoo_sync_status", length = 20)
    @Builder.Default
    private String odooSyncStatus = "SYNCED";

    // ── Metadata ──────────────────────────────────────────────────────────────
    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

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
