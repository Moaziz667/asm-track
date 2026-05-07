package com.asm.delivery.controller;

import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.RouteStatus;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.RouteRepository;
import com.asm.delivery.transport.DriverDTO;
import com.asm.delivery.transport.TransportPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin/fleet/drivers")
@Tag(name = "Fleet Drivers", description = "Driver availability and performance for route planning")
@SecurityRequirement(name = "Bearer Authentication")
@RequiredArgsConstructor
public class AdminDriverController {

    private final TransportPort      transportPort;
    private final RouteRepository    routeRepository;
    private final DeliveryRepository deliveryRepository;

    private static final List<RouteStatus>    ACTIVE_STATUSES   = List.of(RouteStatus.VALIDATED, RouteStatus.IN_PROGRESS);
    private static final List<DeliveryStatus> TERMINAL_STATUSES = List.of(
            DeliveryStatus.DELIVERED, DeliveryStatus.PARTIALLY_DELIVERED,
            DeliveryStatus.FAILED, DeliveryStatus.CANCELLED);

    @GetMapping
    @Operation(summary = "List all active drivers")
    public ResponseEntity<List<DriverDTO>> list() {
        return ResponseEntity.ok(transportPort.getAvailableDrivers());
    }

    @GetMapping("/available")
    @Operation(summary = "List drivers available for a given date and time window")
    public ResponseEntity<List<DriverDTO>> available(
            @RequestParam LocalDate date,
            @RequestParam LocalTime startTime,
            @RequestParam LocalTime endTime) {

        Set<UUID> busyIds = routeRepository.findConflictingDriverIds(date, startTime, endTime, ACTIVE_STATUSES);
        List<DriverDTO> available = transportPort.getAvailableDrivers().stream()
                .filter(d -> !busyIds.contains(UUID.fromString(d.getId())))
                .toList();
        return ResponseEntity.ok(available);
    }

    /**
     * Returns drivers who have delivered for this company, with their stats.
     * companyFilter is applied automatically — each driver's counts reflect
     * only this company's deliveries.
     */
    @GetMapping("/stats")
    @Transactional(readOnly = true)
    @Operation(summary = "Driver performance stats scoped to the calling company")
    public ResponseEntity<List<DriverStatsDTO>> driverStats() {
        Set<UUID> driverIds = deliveryRepository.findDistinctDriverIds(TERMINAL_STATUSES);
        if (driverIds.isEmpty()) return ResponseEntity.ok(List.of());

        List<DriverStatsDTO> result = new ArrayList<>();
        for (UUID driverId : driverIds) {
            DriverDTO driver = transportPort.getDriver(driverId.toString());
            if (driver == null) continue;

            List<com.asm.delivery.entity.Delivery> deliveries =
                    deliveryRepository.findByDriverIdAndStatuses(driverId, TERMINAL_STATUSES);

            long total     = deliveries.size();
            long delivered = deliveries.stream().filter(d ->
                    d.getStatus() == DeliveryStatus.DELIVERED ||
                    d.getStatus() == DeliveryStatus.PARTIALLY_DELIVERED).count();
            long failed    = deliveries.stream().filter(d -> d.getStatus() == DeliveryStatus.FAILED).count();
            int  rate      = total > 0 ? (int) Math.round((delivered * 100.0) / total) : 0;

            result.add(new DriverStatsDTO(
                    driverId.toString(), driver.getName(), driver.getPhone(),
                    (int) total, (int) delivered, (int) failed, rate));
        }

        result.sort(Comparator.comparingInt(DriverStatsDTO::totalDeliveries).reversed());
        return ResponseEntity.ok(result);
    }

    public record DriverStatsDTO(
            String id, String name, String phone,
            int totalDeliveries, int delivered, int failed, int successRate) {}
}
