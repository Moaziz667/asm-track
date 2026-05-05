package com.asm.driver.repository;

import com.asm.driver.entity.DriverStats;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface DriverStatsRepository extends JpaRepository<DriverStats, UUID> {
    Optional<DriverStats> findByDriverId(UUID driverId);
}
