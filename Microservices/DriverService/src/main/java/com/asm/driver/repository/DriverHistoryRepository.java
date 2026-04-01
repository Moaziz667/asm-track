package com.asm.driver.repository;

import com.asm.driver.entity.DriverHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DriverHistoryRepository extends JpaRepository<DriverHistory, UUID> {
    List<DriverHistory> findByDriverIdOrderByCreatedAtDesc(UUID driverId);
    boolean existsByDeliveryIdAndStatus(String deliveryId, String status);
}
