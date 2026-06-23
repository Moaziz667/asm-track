package com.asm.delivery.repository;

import com.asm.delivery.entity.DeliveryStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryStatusHistoryRepository extends JpaRepository<DeliveryStatusHistory, UUID> {
    List<DeliveryStatusHistory> findByDeliveryIdOrderByChangedAtAsc(UUID deliveryId);

    /** Batch load for actor-name prefetch across a whole route's deliveries (avoids per-row lookups). */
    List<DeliveryStatusHistory> findByDeliveryIdIn(Collection<UUID> deliveryIds);
}
