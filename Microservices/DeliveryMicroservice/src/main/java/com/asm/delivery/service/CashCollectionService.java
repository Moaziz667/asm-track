package com.asm.delivery.service;

import com.asm.delivery.dto.request.ProofOfDeliveryRequest.CashCollectionEntry;
import com.asm.delivery.entity.CashCollection;
import com.asm.delivery.entity.CashCollectionStatus;
import com.asm.delivery.entity.CashMethod;
import com.asm.delivery.entity.Order;
import com.asm.delivery.repository.CashCollectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Records what a driver reports collecting, at the moment he proves the delivery.
 *
 * <h2>What this service will not do</h2>
 * It never blocks a delivery. A driver standing at a shop door with an emptied van has already
 * delivered; refusing his proof because a money field is missing would leave the delivery
 * unrecorded, which is worse in every direction — the customer is not credited, the stock is not
 * moved, and the cash is still in his pocket either way. Anything wrong or missing about the money
 * is written down as such and surfaced to the depot, never used as a gate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CashCollectionService {

    private final CashCollectionRepository repo;
    private final FailureReasonService failureReasonService;

    /**
     * Reason recorded when a COD delivery is proved without any collection report.
     *
     * <p>The alternative — writing no row at all — would make the money invisible: the delivery would
     * look complete and the expected sum would never appear in anyone's reconciliation. A row saying
     * "nobody said" is auditable; silence is not.
     */
    static final String REASON_NOT_REPORTED = "COD_NOT_REPORTED";

    /**
     * Create the collection for a delivery being proved. No-op when the order carries no instruction.
     *
     * @return the saved collection, or {@code null} when there was nothing to record
     */
    public CashCollection recordAtPod(Order order, UUID deliveryId, UUID driverId, CashCollectionEntry entry) {
        if (order == null) return null;

        if (!Boolean.TRUE.equals(order.getCodRequired())) {
            if (entry != null && positive(entry.getAmountCollected())) {
                // Money taken against an order nobody asked to collect on. Not silently dropped: it
                // means either the mapping is wrong or the driver collected something he should not
                // have, and both need a human to look.
                log.warn("CASH_UNEXPECTED deliveryId={} driverId={} amount={} — order is not COD; not recorded",
                        deliveryId, driverId, entry.getAmountCollected());
            }
            return null;
        }

        // Re-proving a delivery must not create a second collection.
        var existing = repo.findByDeliveryId(deliveryId);
        if (existing.isPresent()) return existing.get();

        BigDecimal expected = order.getCodAmount() != null ? order.getCodAmount() : BigDecimal.ZERO;
        BigDecimal collected = entry != null && entry.getAmountCollected() != null
                ? entry.getAmountCollected() : BigDecimal.ZERO;
        if (collected.signum() < 0) collected = BigDecimal.ZERO;

        CashMethod method = resolveMethod(entry, collected);
        CashCollectionStatus status = CashCollection.statusFor(expected, collected);

        String reason = entry != null ? trimToNull(entry.getReason()) : null;
        if (status != CashCollectionStatus.COLLECTED && reason == null) {
            reason = REASON_NOT_REPORTED;
            log.warn("CASH_UNREPORTED deliveryId={} driverId={} expected={} collected={} — "
                            + "recorded without a reason from the driver",
                    deliveryId, driverId, expected, collected);
        }
        String reasonLabel = reason != null
                ? failureReasonService.findLabel(reason).orElse(null)
                : null;

        CashCollection collection = CashCollection.builder()
                .deliveryId(deliveryId)
                .orderId(order.getId())
                .amountExpected(expected)
                .amountCollected(collected)
                .method(method)
                .chequeNumber(method == CashMethod.CHEQUE && entry != null ? trimToNull(entry.getChequeNumber()) : null)
                .chequeBank(method == CashMethod.CHEQUE && entry != null ? trimToNull(entry.getChequeBank()) : null)
                .chequeDate(method == CashMethod.CHEQUE && entry != null ? parseDate(entry.getChequeDate()) : null)
                .reason(reason)
                .reasonLabel(reasonLabel)
                .status(status)
                .driverId(driverId)
                .build();

        try {
            CashCollection saved = repo.save(collection);
            log.info("CASH_COLLECTED deliveryId={} driverId={} expected={} collected={} method={} status={}",
                    deliveryId, driverId, expected, collected, method, status);
            return saved;
        } catch (DataIntegrityViolationException race) {
            // Two proofs landing at once; the unique constraint on delivery_id decided which won.
            return repo.findByDeliveryId(deliveryId).orElse(null);
        }
    }

    /**
     * A cheque with no number is downgraded to cash rather than rejected.
     *
     * <p>The database refuses {@code CHEQUE} without a number, so accepting the driver's word as-is
     * would throw and cost him his proof of delivery. The money was still handed over; recording it
     * as an amount with a weaker label keeps the sum in the custody chain, which is what matters at
     * the depot tonight.
     */
    private CashMethod resolveMethod(CashCollectionEntry entry, BigDecimal collected) {
        if (collected.signum() <= 0) return CashMethod.NONE;
        String raw = entry != null ? trimToNull(entry.getMethod()) : null;
        if (raw == null) return CashMethod.CASH;
        try {
            CashMethod m = CashMethod.valueOf(raw.trim().toUpperCase());
            if (m == CashMethod.CHEQUE && trimToNull(entry.getChequeNumber()) == null) {
                log.warn("CASH_CHEQUE_NO_NUMBER — recorded as CASH so the amount stays in the chain");
                return CashMethod.CASH;
            }
            return m == CashMethod.NONE ? CashMethod.CASH : m;
        } catch (IllegalArgumentException unknown) {
            log.warn("CASH_METHOD_UNKNOWN value={} — recorded as CASH", raw);
            return CashMethod.CASH;
        }
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return LocalDate.parse(iso.trim());
        } catch (Exception e) {
            log.warn("CASH_CHEQUE_DATE_UNPARSEABLE value={}", iso);
            return null;
        }
    }
}
