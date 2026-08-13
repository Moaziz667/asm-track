package com.asm.driver.service;

import com.asm.driver.dto.response.InternalDriverResponse;
import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverAccountStatus;
import com.asm.driver.entity.DriverHistory;
import com.asm.driver.entity.DriverStats;
import com.asm.driver.exception.AppException;
import com.asm.driver.repository.DriverHistoryRepository;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.repository.DriverStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import com.asm.driver.entity.DriverOnlineStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InternalDriverService {

    private final DriverRepository driverRepo;
    private final DriverStatsRepository statsRepo;
    private final DriverHistoryRepository historyRepo;
    private final DriverEventPublisher eventPublisher;

    public List<InternalDriverResponse> getAvailableDrivers() {
        List<Driver> drivers = driverRepo.findByAccountStatus(DriverAccountStatus.ACTIVE);
        return drivers.stream().map(this::mapToInternal).collect(Collectors.toList());
    }

    public InternalDriverResponse getDriver(UUID driverId) {
        return driverRepo.findById(driverId)
                .map(this::mapToInternal)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
    }

    public List<InternalDriverResponse> getDriversBatch(List<UUID> ids) {
        return driverRepo.findAllById(ids).stream()
                .map(this::mapToInternal)
                .collect(Collectors.toList());
    }

    @Transactional
    public void updateLocation(UUID driverId, BigDecimal lat, BigDecimal lng) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setCurrentLat(lat);
        driver.setCurrentLng(lng);
        driver.setLastLocationAt(LocalDateTime.now());
        driverRepo.save(driver);
    }

    /**
     * Applies what DeliveryService sees of the driver's realtime connection.
     *
     * <p>Presence is the source of truth for reachability, so it writes the status directly instead
     * of letting a sweep infer one. Two rules keep it honest:
     *
     * <p>ON_BREAK survives a connection. The driver chose it, his phone is still answering, and
     * flipping him back to ONLINE would tell a dispatcher he is available when he said he is not.
     * A disconnection does end it — a phone that has gone silent is unreachable, break or not.
     *
     * <p>The sighting is always recorded, even when the status does not move, because the sweep
     * dates a driver from it. Without that, a long and perfectly healthy session would be swept
     * away for being quiet.
     */
    @Transactional
    public void applyPresence(UUID driverId, boolean connected, LocalDateTime seenAt) {
        Driver driver = driverRepo.findById(driverId).orElse(null);
        if (driver == null) return;                      // unknown driver — nothing to retry

        if (connected && (seenAt != null)
                && (driver.getLastSeenAt() == null || driver.getLastSeenAt().isBefore(seenAt))) {
            driver.setLastSeenAt(seenAt);
        }

        DriverOnlineStatus previous = driver.getOnlineStatus();
        DriverOnlineStatus next = connected
                ? (previous == DriverOnlineStatus.ON_BREAK ? DriverOnlineStatus.ON_BREAK : DriverOnlineStatus.ONLINE)
                : DriverOnlineStatus.OFFLINE;

        driver.setOnlineStatus(next);
        driverRepo.save(driver);

        // The admin screens follow this event; without it a badge would wait for the next poll.
        if (previous != next) {
            eventPublisher.publishStatusChanged(driverId, previous, next, driver.getName());
        }
    }

    @Transactional
    public void incrementStat(UUID driverId, String field, String deliveryId) {
        DriverStats stats = statsRepo.findByDriverId(driverId).orElseGet(() -> {
            DriverStats newStats = DriverStats.builder().driverId(driverId).build();
            return statsRepo.save(newStats);
        });

        stats.setTotalDeliveries(stats.getTotalDeliveries() + 1);
        switch (field.toLowerCase()) {
            case "delivered" -> stats.setDelivered(stats.getDelivered() + 1);
            case "failed" -> stats.setFailed(stats.getFailed() + 1);
            case "cancelled" -> stats.setCancelled(stats.getCancelled() + 1);
            default -> throw AppException.badRequest("Invalid stat field");
        }
        statsRepo.save(stats);

        if (deliveryId != null && !historyRepo.existsByDeliveryIdAndStatus(deliveryId, field.toUpperCase())) {
            historyRepo.save(DriverHistory.builder()
                    .driverId(driverId)
                    .deliveryId(deliveryId)
                    .status(field.toUpperCase())
                    .build());
        }
    }

    private InternalDriverResponse mapToInternal(Driver d) {
        return InternalDriverResponse.builder()
                .id(d.getId().toString())
                .name(d.getName())
                .phone(d.getPhone())
                .currentLat(d.getCurrentLat())
                .currentLng(d.getCurrentLng())
                .lastLocationAt(d.getLastLocationAt())
                .createdAt(d.getCreatedAt())
                .fcmToken(d.getFcmToken())
                .active(d.getAccountStatus() == DriverAccountStatus.ACTIVE)
                .onlineStatus(d.getOnlineStatus() != null ? d.getOnlineStatus().name() : "OFFLINE")
                .photoUrl(d.getPhotoUrl())
                .build();
    }
}
