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
     * Total held by one driver right now. Sums the reported amounts, not the expected ones: the
     * question is how much cash exists, not how much should have been taken.
     */
    @Query("""
        SELECT COALESCE(SUM(c.amountCollected), 0)
        FROM CashCollection c
        WHERE c.driverId = :driverId AND c.remittanceId IS NULL
        """)
    BigDecimal outstandingForDriver(@Param("driverId") UUID driverId);

    /** Total held across the whole fleet — the "cash in circulation" figure no ERP can produce. */
    @Query("""
        SELECT COALESCE(SUM(c.amountCollected), 0)
        FROM CashCollection c
        WHERE c.remittanceId IS NULL
        """)
    BigDecimal outstandingTotal();

    /** Drivers currently holding money, with how much — drives the COD desk. */
    @Query("""
        SELECT c.driverId, COALESCE(SUM(c.amountCollected), 0), COUNT(c)
        FROM CashCollection c
        WHERE c.remittanceId IS NULL AND c.amountCollected > 0
        GROUP BY c.driverId
        """)
    List<Object[]> outstandingByDriver();
}
