package com.asm.delivery.repository;

import com.asm.delivery.entity.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteStopRepository extends JpaRepository<RouteStop, UUID> {
    List<RouteStop> findByRouteIdOrderByStopOrderAsc(UUID routeId);

    Optional<RouteStop> findByRouteIdAndId(UUID routeId, UUID id);

    Optional<RouteStop> findByDeliveryId(UUID deliveryId);

    boolean existsByDeliveryId(UUID deliveryId);
}
