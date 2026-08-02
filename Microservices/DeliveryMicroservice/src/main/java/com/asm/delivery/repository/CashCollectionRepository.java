package com.asm.delivery.repository;

import com.asm.delivery.entity.CashCollection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashCollectionRepository extends JpaRepository<CashCollection, UUID> {

    Optional<CashCollection> findByDeliveryId(UUID deliveryId);

    /** Everything this driver has taken and not yet handed over — the money currently in his pocket. */
    List<CashCollection> findByDriverIdAndRemittanceIdIsNull(UUID driverId);

    List<CashCollection> findByRemittanceId(UUID remittanceId);

    /**
     * Money that has left a customer's hands but not yet reached the depot's.
     *
     * <p>"Not yet handed over" is <b>not</b> the same as "not attached to a handover". A driver who
     * has declared is standing at the counter with the notes still in his hand; nobody has counted
     * them, and if he walks out they are gone exactly as if he had never declared. Counting only
     * unattached collections would make the fleet total drop the moment a driver announces an
     * intention — which is the one moment it must not.
     *
     * <p>So the money leaves circulation when it is <em>received</em>, not when it is promised.
     */
    String STILL_HELD = """
        (c.remittanceId IS NULL
         OR EXISTS (SELECT 1 FROM CashRemittance r
                    WHERE r.id = c.remittanceId
                      AND r.status IN (com.asm.delivery.entity.CashRemittanceStatus.OPEN,
                                       com.asm.delivery.entity.CashRemittanceStatus.DECLARED)))
        """;

    /**
     * Total held by one driver right now. Sums the reported amounts, not the expected ones: the
     * question is how much cash exists, not how much should have been taken.
     */
    @Query("SELECT COALESCE(SUM(c.amountCollected), 0) FROM CashCollection c "
            + "WHERE c.driverId = :driverId AND " + STILL_HELD)
    BigDecimal outstandingForDriver(@Param("driverId") UUID driverId);

    /** Total held across the whole fleet — the "cash in circulation" figure no ERP can produce. */
    @Query("SELECT COALESCE(SUM(c.amountCollected), 0) FROM CashCollection c WHERE " + STILL_HELD)
    BigDecimal outstandingTotal();

    /** Drivers currently holding money, with how much — drives the COD desk. */
    @Query("SELECT c.driverId, COALESCE(SUM(c.amountCollected), 0), COUNT(c) FROM CashCollection c "
            + "WHERE c.amountCollected > 0 AND " + STILL_HELD + " GROUP BY c.driverId")
    List<Object[]> outstandingByDriver();
}
