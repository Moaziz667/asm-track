package com.asm.delivery.sla;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SlaStateRepository extends JpaRepository<SlaState, UUID> {
    Optional<SlaState> findByDeliveryId(UUID deliveryId);

    long countByHealth(SlaHealth health);
}
