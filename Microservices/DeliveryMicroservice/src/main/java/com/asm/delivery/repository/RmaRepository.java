package com.asm.delivery.repository;

import com.asm.delivery.entity.Rma;
import com.asm.delivery.entity.RmaStatus;
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

    long countByStatus(RmaStatus status);

    /** [status, count] tallies for the returns KPI. */
    @Query("SELECT r.status, COUNT(r) FROM Rma r GROUP BY r.status")
    List<Object[]> countGroupedByStatus();

    /** Total returned units (sum of item quantities) for restocked RMAs in a window. */
    @Query("""
            SELECT COALESCE(SUM(i.quantity), 0) FROM Rma r JOIN r.items i
            WHERE r.status = :status AND r.createdAt BETWEEN :start AND :end
            """)
    long sumReturnedUnits(@Param("status") RmaStatus status,
                          @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end);
}
