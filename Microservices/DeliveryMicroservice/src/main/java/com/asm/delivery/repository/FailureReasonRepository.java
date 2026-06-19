package com.asm.delivery.repository;

import com.asm.delivery.entity.FailureReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FailureReasonRepository extends JpaRepository<FailureReason, UUID> {

    List<FailureReason> findAllByOrderBySortOrderAscLabelAsc();

    List<FailureReason> findByActiveTrueOrderBySortOrderAscLabelAsc();

    Optional<FailureReason> findByCode(String code);

    boolean existsByCode(String code);
}
