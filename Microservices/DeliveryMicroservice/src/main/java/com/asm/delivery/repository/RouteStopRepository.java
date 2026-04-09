package com.asm.delivery.repository;

import com.asm.delivery.entity.RouteStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteStopRepository extends JpaRepository<RouteStop, UUID> {
    List<RouteStop> findByRouteIdOrderByStopOrderAsc(UUID routeId);

    Optional<RouteStop> findByRouteIdAndId(UUID routeId, UUID id);

    Optional<RouteStop> findByDeliveryId(UUID deliveryId);

    @Query("SELECT rs FROM RouteStop rs JOIN FETCH rs.route r WHERE rs.deliveryId IN :deliveryIds")
    List<RouteStop> findAllByDeliveryIdInWithRoute(@Param("deliveryIds") List<UUID> deliveryIds);

    boolean existsByDeliveryId(UUID deliveryId);

    void deleteByRouteId(UUID routeId);

    /** All non-terminal stops from active routes, independent from legacy SLA status fields. */
    @Query("""
        SELECT rs FROM RouteStop rs JOIN FETCH rs.route r
        WHERE r.status IN ('VALIDATED', 'IN_PROGRESS')
        AND rs.status IN ('PENDING', 'ARRIVED')
        ORDER BY r.date ASC, r.plannedStartTime ASC, rs.stopOrder ASC
    """)
    List<RouteStop> findActivePendingStops();
}
