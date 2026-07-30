package com.asm.delivery.repository;

import com.asm.delivery.entity.ErpSyncEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Reads of the ERP sync journal. The console only ever wants the recent tail, so every query is
 * bounded by a {@link Pageable} rather than returning the whole table.
 */
public interface ErpSyncEventRepository extends JpaRepository<ErpSyncEvent, UUID> {

    /**
     * The newest attempts, optionally narrowed to the failures.
     *
     * <p>{@code failedOnly} is a plain boolean rather than a nullable filter: a nullable parameter
     * compared with {@code IS NULL} leaves Postgres unable to infer the parameter type (42P18).
     */
    @Query("""
            SELECT e FROM ErpSyncEvent e
            WHERE (:failedOnly = false OR e.success = false)
            ORDER BY e.occurredAt DESC
            """)
    List<ErpSyncEvent> recent(@Param("failedOnly") boolean failedOnly, Pageable page);

    /** Everything this one order has been through, newest first — the per-delivery drill-down. */
    List<ErpSyncEvent> findByOrderIdOrderByOccurredAtDesc(UUID orderId);

    long countByOccurredAtAfter(LocalDateTime since);

    long countBySuccessFalseAndOccurredAtAfter(LocalDateTime since);
}
