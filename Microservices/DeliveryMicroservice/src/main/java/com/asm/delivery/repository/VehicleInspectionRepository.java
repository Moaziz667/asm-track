package com.asm.delivery.repository;

import com.asm.delivery.entity.VehicleInspection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VehicleInspectionRepository extends JpaRepository<VehicleInspection, UUID> {
    
    Optional<VehicleInspection> findFirstByVehicleIdAndDriverIdOrderByInspectedAtDesc(UUID vehicleId, UUID driverId);

    boolean existsByVehicleIdAndDriverIdAndInspectedAtAfter(UUID vehicleId, UUID driverId, LocalDateTime threshold);
}
