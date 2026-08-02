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

    long countByStatus(CashRemittanceStatus status);
}
