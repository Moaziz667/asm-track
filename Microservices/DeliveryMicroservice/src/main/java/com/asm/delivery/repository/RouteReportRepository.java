package com.asm.delivery.repository;

import com.asm.delivery.entity.RouteReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteReportRepository extends JpaRepository<RouteReport, UUID> {
    Optional<RouteReport> findByRouteId(UUID routeId);
}
