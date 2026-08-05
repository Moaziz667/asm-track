package com.asm.delivery.repository;

import com.asm.delivery.entity.CashRemittance;
import com.asm.delivery.entity.CashRemittanceStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CashRemittanceRepository extends JpaRepository<CashRemittance, UUID> {

    /** The handover a driver currently has in flight, if any. At most one — enforced by a partial index. */
    Optional<CashRemittance> findFirstByDriverIdAndStatusIn(UUID driverId, List<CashRemittanceStatus> statuses);

    Page<CashRemittance> findByStatusIn(List<CashRemittanceStatus> statuses, Pageable pageable);

    Page<CashRemittance> findByDriverId(UUID driverId, Pageable pageable);

    /**
     * How many handovers sit in each state, in one pass.
     *
     * <p>Replaces a per-status {@code countByStatus} that nothing ever called. The desk needs all
     * five figures at once to label its tabs, and asking five times for a bar that renders once is
     * five round trips for one answer.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT r.status, COUNT(r) FROM CashRemittance r GROUP BY r.status")
    List<Object[]> countByStatus();
}
