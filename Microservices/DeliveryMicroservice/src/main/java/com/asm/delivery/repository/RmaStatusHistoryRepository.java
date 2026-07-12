package com.asm.delivery.repository;

import com.asm.delivery.entity.RmaStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RmaStatusHistoryRepository extends JpaRepository<RmaStatusHistory, UUID> {

    List<RmaStatusHistory> findByRmaIdOrderByCreatedAtAsc(UUID rmaId);
}
