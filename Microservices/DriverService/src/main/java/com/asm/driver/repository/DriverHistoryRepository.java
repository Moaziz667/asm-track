package com.asm.driver.repository;

import com.asm.driver.entity.DriverHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DriverHistoryRepository extends JpaRepository<DriverHistory, UUID> {
    List<DriverHistory> findByDriverIdOrderByCreatedAtDesc(UUID driverId);

    /** Paged driver history (Slice = no count query) — backs the mobile infinite-scroll history. */
    Slice<DriverHistory> findByDriverIdOrderByCreatedAtDesc(UUID driverId, Pageable pageable);

    boolean existsByDeliveryIdAndStatus(String deliveryId, String status);
}
