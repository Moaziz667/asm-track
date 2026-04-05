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

    /** All non-terminal stops from VALIDATED or IN_PROGRESS routes that have a computed SLA status. */
    @Query("""
        SELECT rs FROM RouteStop rs JOIN FETCH rs.route r
        WHERE r.status IN ('VALIDATED', 'IN_PROGRESS')
        AND rs.status IN ('PENDING', 'ARRIVED')
        AND rs.slaStatus IS NOT NULL
        ORDER BY rs.slaStatus ASC, rs.etaAt ASC
    """)
    List<RouteStop> findActivePendingStopsWithSla();
}
