package com.asm.delivery.repository;

import com.asm.delivery.entity.Depot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DepotRepository extends JpaRepository<Depot, UUID> {
    List<Depot> findAllByOrderByCreatedAtDesc();
    List<Depot> findByIsActiveTrue();
}
