package com.asm.delivery.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The handover of a driver's cash to the depot.
 *
 * <h2>Why this table is the point of the whole module</h2>
 * Between the moment a shop pays and the moment the accountant posts it, the money exists only in a
 * driver's pocket. The ERP cannot see that interval — it learns of the payment once it has already
 * arrived, often the next day. So nobody can answer "how much is out there right now", which is the
 * question a depot manager actually has at 6pm.
 *
 * <h2>Two figures, two people</h2>
 * {@link #declaredTotal} is what the driver says he has. {@link #receivedTotal} is what someone else
 * counted. They are stored separately and the discrepancy is derived, never typed: a single
 * "amount" field filled by one person is an honour system with extra steps. The service refuses to
 * let the same user do both.
 */
@Entity
@Table(name = "cash_remittances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CashRemittance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    /** Snapshotted so a driver renamed or deactivated later still reads correctly on old handovers. */
    @Column(name = "driver_name", length = 150)
    private String driverName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private CashRemittanceStatus status = CashRemittanceStatus.OPEN;

    /**
     * Sum of the collections attached to this handover, computed from them at declaration time.
     *
     * <p>Not editable: it is the platform's own account of what the driver took, and it is the only
     * figure in the row that neither party can influence.
     */
    @Column(name = "expected_total", nullable = false, precision = 19, scale = 3)
    @Builder.Default
    private BigDecimal expectedTotal = BigDecimal.ZERO;

    /** What the driver says he is handing over. */
    @Column(name = "declared_total", precision = 19, scale = 3)
    private BigDecimal declaredTotal;

    /** What the receiver counted. */
    @Column(name = "received_total", precision = 19, scale = 3)
    private BigDecimal receivedTotal;

    /** {@code receivedTotal - expectedTotal}. Negative means money is missing. Derived, never typed. */
    @Column(name = "discrepancy", precision = 19, scale = 3)
    private BigDecimal discrepancy;

    @Column(name = "declared_by")
    private UUID declaredBy;

    @Column(name = "declared_at")
    private LocalDateTime declaredAt;

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "received_by_name", length = 150)
    private String receivedByName;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /** Who settled a discrepancy, and when. Null when the handover balanced on its own. */
    @Column(name = "reconciled_by")
    private UUID reconciledBy;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    /**
     * The manager's explanation for a discrepancy — the audit trail's payload, and the only note on
     * this row. The count step used to write one too; it was optional, nobody filled it, and nothing
     * aggregated it. Two free-text fields where one is mandatory and the other decorative teach a
     * cashier that notes are decorative.
     */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "opened_at", nullable = false)
    @Builder.Default
    private LocalDateTime openedAt = LocalDateTime.now();

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
