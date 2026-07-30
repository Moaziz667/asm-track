package com.asm.delivery.repository;

import com.asm.delivery.entity.Rma;
import com.asm.delivery.entity.RmaStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface RmaRepository extends JpaRepository<Rma, UUID> {

    /** Atomic next value of the RMA reference sequence (see V28). */
    @Query(value = "SELECT nextval('rma_number_seq')", nativeQuery = true)
    long nextRmaNumberSeq();

    /** Own human-readable RMA reference, e.g. RET-00042. */
    default String nextRmaNumber() {
        return String.format("RET-%05d", nextRmaNumberSeq());
    }

    List<Rma> findAllByOrderByCreatedAtDesc();

    List<Rma> findByStatusOrderByCreatedAtDesc(RmaStatus status);

    List<Rma> findByDeliveryIdOrderByCreatedAtDesc(UUID deliveryId);

    /**
     * Server-side paginated + filtered returns list. status/q are optional (null status = all, blank q =
     * no text filter); from/to are always bound (the caller passes a wide sentinel range when absent — a
     * bare ":from IS NULL" on a timestamp param breaks Postgres type inference). Search spans client name /
     * BL / ERP ref (all on the Rma row).
     */
    @Query("""
            SELECT r FROM Rma r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:q IS NULL OR :q = '' OR
                   LOWER(r.clientName) LIKE LOWER(CONCAT('%', :q, '%')) OR
                   LOWER(r.blNumber)   LIKE LOWER(CONCAT('%', :q, '%')) OR
                   LOWER(r.erpOrderId) LIKE LOWER(CONCAT('%', :q, '%')))
              AND r.createdAt >= :from
              AND r.createdAt <= :to
            ORDER BY r.createdAt DESC
            """)
    Page<Rma> searchPaged(@Param("status") RmaStatus status, @Param("q") String q,
                          @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, Pageable pageable);

    long countByStatus(RmaStatus status);

    /** [status, count] tallies for the returns KPI. */
    @Query("SELECT r.status, COUNT(r) FROM Rma r GROUP BY r.status")
    List<Object[]> countGroupedByStatus();

    /** Monetary value of all non-terminal-negative returns (excludes REJECTED/CANCELLED) — for the KPI bar. */
    @Query("""
            SELECT COALESCE(SUM(i.unitPrice * i.quantity), 0) FROM Rma r JOIN r.items i
            WHERE r.status NOT IN ('REJECTED', 'CANCELLED')
            """)
    java.math.BigDecimal sumReturnValue();

    /** Per-SKU quantities already physically returned (RECEIVED or RESTOCKED) for a given delivery. */
    @Query("""
            SELECT i.sku, SUM(i.quantity) FROM Rma r JOIN r.items i
            WHERE r.deliveryId = :deliveryId AND r.status IN ('RECEIVED', 'RESTOCKED')
            GROUP BY i.sku
            """)
    List<Object[]> sumReturnedQtyBySku(@Param("deliveryId") UUID deliveryId);

    /**
     * One return that made it back into stock, as evidence for the integration rehearsal.
     *
     * <p>RESTOCKED rather than RECEIVED: the ERP write happens when the goods go back on the shelf,
     * so an RMA stopping short of that has exercised nothing.
     *
     * @param since only count returns created after this — see
     *              {@link DeliveryRepository#findSyncedWithStatus}
     */
    @Query("""
            SELECT r FROM Rma r
            WHERE r.status = com.asm.delivery.entity.RmaStatus.RESTOCKED
              AND (:since IS NULL OR r.createdAt > :since)
            ORDER BY r.createdAt DESC
            """)
    List<Rma> findRestocked(@Param("since") java.time.LocalDateTime since,
                            org.springframework.data.domain.Pageable pageable);
}
