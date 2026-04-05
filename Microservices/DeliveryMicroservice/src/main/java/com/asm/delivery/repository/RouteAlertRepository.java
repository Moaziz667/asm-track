package com.asm.delivery.repository;

import com.asm.delivery.entity.RouteAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RouteAlertRepository extends JpaRepository<RouteAlert, UUID> {
    List<RouteAlert> findByRouteIdAndAcknowledgedFalseOrderByCreatedAtDesc(UUID routeId);
    List<RouteAlert> findByRouteIdOrderByCreatedAtDesc(UUID routeId);

    @Modifying
    @Query("UPDATE RouteAlert a SET a.acknowledged = true WHERE a.routeId = :routeId")
    void acknowledgeAllForRoute(UUID routeId);

    boolean existsByRouteIdAndStopIdAndAlertTypeAndAcknowledgedFalse(
            UUID routeId, UUID stopId,
            com.asm.delivery.entity.AlertType alertType);
}
