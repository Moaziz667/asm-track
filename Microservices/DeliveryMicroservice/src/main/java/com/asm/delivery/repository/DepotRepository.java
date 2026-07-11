package com.asm.delivery.repository;

import com.asm.delivery.entity.Depot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepotRepository extends JpaRepository<Depot, UUID> {
    List<Depot> findAllByOrderByCreatedAtDesc();
    List<Depot> findByIsActiveTrue();

    /** Resolve the depot mirroring an ERP warehouse code (1:1 sync/resolution key). */
    Optional<Depot> findByWarehouseCode(String warehouseCode);

    @Query("""
            SELECT d FROM Depot d
            WHERE d.isActive = true AND (
                LOWER(d.name)    LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
                OR LOWER(d.address) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
            )
            ORDER BY d.name ASC
            """)
    List<Depot> searchByQuery(@Param("q") String q, Pageable pageable);
}
