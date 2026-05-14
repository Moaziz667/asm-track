package com.asm.delivery.repository;

import com.asm.delivery.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    
    /**
     * Finds pending events using a 'FOR UPDATE SKIP LOCKED' lock.
     * This ensures that multiple instances of the microservice do not
     * pick up the same event simultaneously.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE status = :status ORDER BY created_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingWithLock(@Param("status") String status, @Param("limit") int limit);

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);
}
