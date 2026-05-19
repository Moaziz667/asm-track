package com.asm.delivery.repository;

import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID>, JpaSpecificationExecutor<Route> {

    List<Route> findAllByOrderByDateDescCreatedAtDesc();

    List<Route> findByStatusIn(List<RouteStatus> statuses);

    List<Route> findAllByDriverIdAndDate(UUID driverId, LocalDate date);

    List<Route> findAllByVehicleIdAndDate(UUID vehicleId, LocalDate date);

    Optional<Route> findByDriverIdAndDate(UUID driverId, LocalDate date);

    List<Route> findByDriverIdAndDateAndStatusIn(UUID driverId, LocalDate date, List<RouteStatus> statuses);

    boolean existsByDriverIdAndDateAndStatusIn(UUID driverId, LocalDate date, List<RouteStatus> statuses);

    @Query("""
        SELECT r.vehicleId FROM Route r
        WHERE r.date = :date
          AND r.status IN :statuses
          AND r.vehicleId IS NOT NULL
          AND r.plannedStartTime < :endTime
          AND r.plannedEndTime > :startTime
    """)
    Set<UUID> findConflictingVehicleIds(
            @Param("date") LocalDate date,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("statuses") List<RouteStatus> statuses);

    @Query("""
        SELECT r.driverId FROM Route r
        WHERE r.date = :date
          AND r.status IN :statuses
          AND r.driverId IS NOT NULL
          AND r.plannedStartTime < :endTime
          AND r.plannedEndTime > :startTime
    """)
    Set<UUID> findConflictingDriverIds(
            @Param("date") LocalDate date,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime,
            @Param("statuses") List<RouteStatus> statuses);

    @Query("""
            SELECT r FROM Route r
            WHERE LOWER(r.name) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY r.date DESC, r.createdAt DESC
            """)
    List<Route> searchByQuery(@Param("q") String q, Pageable pageable);

    @Query("""
        SELECT r FROM Route r
        LEFT JOIN FETCH r.stops s
        WHERE r.id = :id
    """)
    Optional<Route> findFullRouteById(@Param("id") UUID id);
}
