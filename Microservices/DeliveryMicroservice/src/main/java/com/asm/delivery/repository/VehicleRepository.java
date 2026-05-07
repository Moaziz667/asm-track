package com.asm.delivery.repository;

import com.asm.delivery.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
