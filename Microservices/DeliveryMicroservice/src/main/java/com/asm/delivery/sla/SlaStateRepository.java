package com.asm.delivery.sla;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SlaStateRepository extends JpaRepository<SlaState, UUID> {
    Optional<SlaState> findByDeliveryId(UUID deliveryId);

    /** Bulk fetch for route/full mapping to avoid a per-stop SLA query (N+1). */
    List<SlaState> findByDeliveryIdIn(List<UUID> deliveryIds);

    List<SlaState> findByHealthIn(List<SlaHealth> healths);

    long countByHealth(SlaHealth health);

    long countByHealthIn(List<SlaHealth> healths);
}
