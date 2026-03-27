package com.asm.delivery.repository;

import com.asm.delivery.entity.ProofOfDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProofOfDeliveryRepository extends JpaRepository<ProofOfDelivery, UUID> {

    Optional<ProofOfDelivery> findByDeliveryId(UUID deliveryId);

    boolean existsByDeliveryId(UUID deliveryId);
}
