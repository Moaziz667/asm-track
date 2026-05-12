package com.asm.delivery.repository;

import com.asm.delivery.entity.ProcessedRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface ProcessedRequestRepository extends JpaRepository<ProcessedRequest, String> {

    @Modifying
    @Transactional
    @Query("DELETE FROM ProcessedRequest p WHERE p.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
