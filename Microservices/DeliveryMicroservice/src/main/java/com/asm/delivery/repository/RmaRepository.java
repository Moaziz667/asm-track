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

    List<Rma> findAllByOrderByCreatedAtDesc();

    List<Rma> findByStatusOrderByCreatedAtDesc(RmaStatus status);

    List<Rma> findByDeliveryIdOrderByCreatedAtDesc(UUID deliveryId);

    /**
     * Server-side paginated + filtered returns list. Both filters are optional: null status = all
     * statuses, blank q = no text filter. Search spans client name / BL / ERP ref (all on the Rma row).
     */
    @Query("""
            SELECT r FROM Rma r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:q IS NULL OR :q = '' OR
                   LOWER(r.clientName) LIKE LOWER(CONCAT('%', :q, '%')) OR
                   LOWER(r.blNumber)   LIKE LOWER(CONCAT('%', :q, '%')) OR
                   LOWER(r.erpOrderId) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:from IS NULL OR r.createdAt >= :from)
              AND (:to   IS NULL OR r.createdAt <= :to)
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
}
