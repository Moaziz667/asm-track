package com.asm.delivery.repository;

import com.asm.delivery.entity.Vehicle;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {
    List<Vehicle> findAllByOrderByCreatedAtDesc();

    Optional<Vehicle> findByPlateIgnoreCase(String plate);

    boolean existsByPlateIgnoreCase(String plate);

    Optional<Vehicle> findFirstByDriverIdAndActiveTrue(UUID driverId);

    List<Vehicle> findByActiveTrue();

    @Query("""
            SELECT v FROM Vehicle v
            WHERE v.active = true AND (
                LOWER(v.plate) LIKE LOWER(CONCAT('%', :q, '%')) OR
                LOWER(v.make)  LIKE LOWER(CONCAT('%', :q, '%')) OR
                LOWER(v.model) LIKE LOWER(CONCAT('%', :q, '%')) OR
                LOWER(v.name)  LIKE LOWER(CONCAT('%', :q, '%'))
            )
            ORDER BY v.createdAt DESC
            """)
    List<Vehicle> searchByQuery(@Param("q") String q, Pageable pageable);
}
