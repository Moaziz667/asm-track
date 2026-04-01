package com.asm.delivery.repository;

import com.asm.delivery.entity.Route;
import com.asm.delivery.entity.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID>, JpaSpecificationExecutor<Route> {
    List<Route> findAllByOrderByDateDescCreatedAtDesc();

    List<Route> findAllByDriverIdAndDate(UUID driverId, LocalDate date);

    Optional<Route> findByDriverIdAndDate(UUID driverId, LocalDate date);

    List<Route> findByDriverIdAndDateAndStatusIn(UUID driverId, LocalDate date, List<RouteStatus> statuses);
}
