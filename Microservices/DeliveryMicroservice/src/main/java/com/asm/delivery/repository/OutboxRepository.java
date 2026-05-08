package com.asm.delivery.repository;

import com.asm.delivery.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, java.util.UUID> {
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(String status);
}
