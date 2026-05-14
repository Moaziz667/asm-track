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

    Optional<RouteStop> findFirstByDeliveryIdOrderByCreatedAtDesc(UUID deliveryId);

    @Query("SELECT rs FROM RouteStop rs JOIN FETCH rs.route r WHERE rs.deliveryId = :deliveryId ORDER BY rs.createdAt DESC")
    List<RouteStop> findStopsByDeliveryIdWithRoute(@Param("deliveryId") UUID deliveryId);

    default Optional<RouteStop> findByDeliveryId(UUID deliveryId) {
        return findFirstByDeliveryIdOrderByCreatedAtDesc(deliveryId);
    }

    default Optional<RouteStop> findByDeliveryIdWithRoute(UUID deliveryId) {
        return findStopsByDeliveryIdWithRoute(deliveryId).stream().findFirst();
    }
    
    @Query("SELECT rs FROM RouteStop rs WHERE rs.deliveryId = :deliveryId AND rs.status NOT IN ('REMOVED_REPLANNED', 'REMOVED_CANCELLED') ORDER BY rs.createdAt DESC")
    List<RouteStop> findActiveStopsByDeliveryId(@Param("deliveryId") UUID deliveryId);

    @Query("SELECT rs FROM RouteStop rs JOIN FETCH rs.route r WHERE rs.deliveryId = :deliveryId AND rs.status NOT IN ('REMOVED_REPLANNED', 'REMOVED_CANCELLED') ORDER BY rs.createdAt DESC")
    List<RouteStop> findActiveStopsByDeliveryIdWithRoute(@Param("deliveryId") UUID deliveryId);

    default Optional<RouteStop> findActiveByDeliveryId(UUID deliveryId) {
        return findActiveStopsByDeliveryId(deliveryId).stream().findFirst();
    }

    default Optional<RouteStop> findActiveByDeliveryIdWithRoute(UUID deliveryId) {
        return findActiveStopsByDeliveryIdWithRoute(deliveryId).stream().findFirst();
    }

    @Query("SELECT rs FROM RouteStop rs JOIN FETCH rs.route r WHERE rs.deliveryId IN :deliveryIds AND rs.status NOT IN ('REMOVED_REPLANNED', 'REMOVED_CANCELLED')")
    List<RouteStop> findAllByDeliveryIdInWithRoute(@Param("deliveryIds") List<UUID> deliveryIds);

    boolean existsByDeliveryId(UUID deliveryId);

    void deleteByRouteId(UUID routeId);

    /** Stops pending handoff confirmation where the given driver is the sender. */
    @Query("SELECT rs FROM RouteStop rs WHERE rs.handoffFromDriverId = :driverId AND rs.requiresHandoff = true AND rs.handoffConfirmedAt IS NULL")
    List<RouteStop> findPendingHandoffsByFromDriver(@Param("driverId") UUID driverId);

    @Query("SELECT rs FROM RouteStop rs JOIN FETCH rs.route r WHERE rs.handoffFromDriverId = :driverId AND rs.requiresHandoff = true AND rs.handoffConfirmedAt IS NULL")
    List<RouteStop> findPendingHandoffsByFromDriverWithRoute(@Param("driverId") UUID driverId);

    /** All non-terminal stops from active routes, independent from legacy SLA status fields. */
    @Query("""
        SELECT rs FROM RouteStop rs JOIN FETCH rs.route r
        WHERE r.status IN ('VALIDATED', 'IN_PROGRESS')
        AND rs.status IN ('PENDING', 'ASSIGNED', 'ARRIVED', 'PICKED_UP', 'IN_TRANSIT')
        ORDER BY r.date ASC, r.plannedStartTime ASC, rs.stopOrder ASC
    """)
    List<RouteStop> findActivePendingStops();
}
