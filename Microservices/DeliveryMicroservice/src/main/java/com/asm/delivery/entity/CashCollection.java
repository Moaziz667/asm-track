package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What the driver reports having taken at one delivery.
 *
 * <h2>A declaration, not an accounting entry</h2>
 * Nothing here posts to a ledger. The row records that a person said they received a sum at a place
 * and a time — the ERP remains the book of accounts, and the money itself never passes through this
 * platform. ASM is a custody register: it answers "who was holding what, and when", which is the one
 * question an ERP cannot answer, because it only ever sees the money once it has already arrived.
 *
 * <h2>Why this is not a column on {@link ProofOfDelivery}</h2>
 * The POD is written once and never changes — that immutability is what makes it evidence. Cash keeps
 * moving after the handover: it is declared at the depot, counted by someone else, disputed, settled.
 * Putting a lifecycle inside the proof would make the proof mutable.
 *
 * <p>An earlier attempt did put it there ({@code deliveries.cod_collected}, dropped in V8) and had
 * nowhere to record a partial payment, a cheque, or a reason for collecting nothing. It was never
 * wired to anything.
 */
@Entity
@Table(name = "cash_collections",
        uniqueConstraints = @UniqueConstraint(name = "uq_cash_collections_delivery",
                columnNames = {"delivery_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** One collection per delivery — enforced by a unique constraint, not by convention. */
    @Column(name = "delivery_id", nullable = false, unique = true)
    private UUID deliveryId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /**
     * What the ERP said to collect, copied at creation and never recomputed.
     *
     * <p>The whole reconciliation compares a driver's report against this figure. If it could move
     * afterwards — because the order was re-synced, or a price changed in the ERP — every past
     * discrepancy would silently become right or wrong. So it is frozen here rather than read
     * through to {@code orders.cod_amount}.
     */
    @Column(name = "amount_expected", nullable = false, precision = 19, scale = 3)
    private BigDecimal amountExpected;

    /** What the driver says he took. Zero on a refusal, less than expected on a partial payment. */
    @Column(name = "amount_collected", nullable = false, precision = 19, scale = 3)
    @Builder.Default
    private BigDecimal amountCollected = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20)
    @Builder.Default
    private CashMethod method = CashMethod.NONE;

    // ── Cheque details — a cheque is a promise, and an unidentifiable one is worthless ──────────
    // Standard practice in Tunisian B2B distribution; without number and bank a bounced cheque
    // cannot be traced back to the delivery that accepted it.

    @Column(name = "cheque_number", length = 60)
    private String chequeNumber;

    @Column(name = "cheque_bank", length = 100)
    private String chequeBank;

    @Column(name = "cheque_date")
    private LocalDate chequeDate;

    /** Failure-reason catalog code when nothing, or not everything, was collected. */
    @Column(name = "reason", length = 60)
    private String reason;

    /** Human label snapshotted from the catalog, so a later rename cannot rewrite history. */
    @Column(name = "reason_label", length = 200)
    private String reasonLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private CashCollectionStatus status = CashCollectionStatus.PENDING;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "collected_at", nullable = false)
    @Builder.Default
    private LocalDateTime collectedAt = LocalDateTime.now();

    /**
     * The handover this collection was included in. Null while the driver still holds the money —
     * which is exactly the set the "cash in circulation" figure is built from.
     */
    @Column(name = "remittance_id")
    private UUID remittanceId;

    /** Derive the status from the reported amount; never set by the caller. */
    public static CashCollectionStatus statusFor(BigDecimal expected, BigDecimal collected) {
        if (collected == null || collected.signum() <= 0) return CashCollectionStatus.REFUSED;
        if (expected == null || collected.compareTo(expected) >= 0) return CashCollectionStatus.COLLECTED;
        return CashCollectionStatus.PARTIAL;
    }
}
