package com.asm.appbackend.repository;

import com.asm.appbackend.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Claims due PENDING events with FOR UPDATE SKIP LOCKED so concurrent instances don't race. */
    @Query(value = "SELECT * FROM outbox_event WHERE status = :status AND next_retry_at <= :now ORDER BY next_retry_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingWithLock(@Param("status") String status, @Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying
    @Transactional
    @Query(value = "UPDATE outbox_event SET status = 'PENDING' WHERE status = 'PROCESSING' AND created_at < :cutoff", nativeQuery = true)
    int recoverStuckEvents(@Param("cutoff") LocalDateTime cutoff);
}
