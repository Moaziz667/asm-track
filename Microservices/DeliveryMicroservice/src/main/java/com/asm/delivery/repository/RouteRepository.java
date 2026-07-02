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

    /** Routes for a given date filtered by status — backs the dispatch desk's active-driver lookup. */
    List<Route> findByDateAndStatusIn(LocalDate date, List<RouteStatus> statuses);

    List<Route> findAllByVehicleIdAndDate(UUID vehicleId, LocalDate date);

    Optional<Route> findByDriverIdAndDate(UUID driverId, LocalDate date);

    List<Route> findByDriverIdAndDateAndStatusIn(UUID driverId, LocalDate date, List<RouteStatus> statuses);

    boolean existsByDriverIdAndDateAndStatusIn(UUID driverId, LocalDate date, List<RouteStatus> statuses);

    @Query("""
        SELECT r.vehicleId FROM Route r
        WHERE r.date = :date
          AND r.status IN :statuses
          AND r.vehicleId IS NOT NULL
          AND (r.plannedStartTime IS NULL OR r.plannedStartTime < :endTime)
          AND (r.plannedEndTime IS NULL OR r.plannedEndTime > :startTime)
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
          AND (r.plannedStartTime IS NULL OR r.plannedStartTime < :endTime)
          AND (r.plannedEndTime IS NULL OR r.plannedEndTime > :startTime)
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

    /** Existing auto-generated route codes (R…) — used to compute the next sequential name. */
    @Query("SELECT r.name FROM Route r WHERE r.name LIKE 'R%'")
    List<String> findGeneratedRouteNames();

    /** Driver ids currently out on a route of any of these statuses (date-independent — "busy now"). */
    @Query("SELECT r.driverId FROM Route r WHERE r.status IN :statuses AND r.driverId IS NOT NULL")
    Set<UUID> findDriverIdsByStatusIn(@Param("statuses") List<RouteStatus> statuses);

    /**
     * [driverId, routeId, status] for a driver's live/assignable routes — backs the fleet list's
     * route-presence flag. Mirrors {@code findOrCreateRouteForDriver}: a running route (IN_PROGRESS)
     * counts regardless of date; VALIDATED is a committed assignment; DRAFT counts only for today (so
     * stale abandoned drafts don't light up the badge). Ordered so the *first* row per driver is the
     * most relevant route (running > validated > draft, newest date first).
     */
    @Query("""
        SELECT r.driverId, r.id FROM Route r
        WHERE r.driverId IS NOT NULL
          AND ( r.status = com.asm.delivery.entity.RouteStatus.IN_PROGRESS
             OR r.status = com.asm.delivery.entity.RouteStatus.VALIDATED
             OR (r.status = com.asm.delivery.entity.RouteStatus.DRAFT AND r.date = :today) )
        ORDER BY
          CASE r.status
            WHEN com.asm.delivery.entity.RouteStatus.IN_PROGRESS THEN 0
            WHEN com.asm.delivery.entity.RouteStatus.VALIDATED   THEN 1
            ELSE 2 END,
          r.date DESC
    """)
    List<Object[]> findActiveDriverRoutes(@Param("today") LocalDate today);

    /** Vehicle ids currently out on a route of any of these statuses (date-independent — "busy now"). */
    @Query("SELECT r.vehicleId FROM Route r WHERE r.status IN :statuses AND r.vehicleId IS NOT NULL")
    Set<UUID> findVehicleIdsByStatusIn(@Param("statuses") List<RouteStatus> statuses);

    @Query("""
        SELECT r FROM Route r
        LEFT JOIN FETCH r.stops s
        WHERE r.id = :id
    """)
    Optional<Route> findFullRouteById(@Param("id") UUID id);
}
