package com.asm.driver.service;

import com.asm.driver.dto.response.InternalDriverResponse;
import com.asm.driver.entity.Driver;
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

    public List<InternalDriverResponse> getAvailableDrivers() {
        List<Driver> drivers = driverRepo.findByActiveTrue();
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
                .active(d.getActive())
                .onlineStatus(d.getOnlineStatus() != null ? d.getOnlineStatus().name() : "OFFLINE")
                .build();
    }
}
