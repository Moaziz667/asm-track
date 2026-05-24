package com.asm.driver.service;

import com.asm.driver.dto.request.LocationRequest;
import com.asm.driver.dto.response.DriverProfileResponse;
import com.asm.driver.dto.response.HistoryResponse;
import com.asm.driver.dto.response.StatsResponse;
import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverOnlineStatus;
import com.asm.driver.entity.DriverStats;
import com.asm.driver.exception.AppException;
import com.asm.driver.repository.DriverHistoryRepository;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.repository.DriverStatsRepository;
import com.asm.driver.service.DriverAuditLogService;
import com.asm.driver.service.DriverEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DriverService {

    private final DriverRepository driverRepo;
    private final DriverStatsRepository statsRepo;
    private final DriverHistoryRepository historyRepo;
    private final PasswordEncoder passwordEncoder;
    private final DriverEventPublisher eventPublisher;
    private final DriverAuditLogService auditLogService;

    public DriverProfileResponse getProfile(UUID driverId) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        return mapToProfile(driver);
    }

    @Transactional
    public void updateProfile(UUID driverId, String name) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setName(name);
        driverRepo.save(driver);
    }

    @Transactional
    public void updatePassword(UUID driverId, String currentPwd, String newPwd) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        if (!passwordEncoder.matches(currentPwd, driver.getPasswordHash())) {
            throw AppException.badRequest("Invalid current password");
        }
        driver.setPasswordHash(passwordEncoder.encode(newPwd));
        driverRepo.save(driver);
    }

    @Transactional
    public void updateLocation(UUID driverId, LocationRequest req) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setCurrentLat(req.getLat());
        driver.setCurrentLng(req.getLng());
        driver.setLastLocationAt(LocalDateTime.now());
        driverRepo.save(driver);
    }

    @Transactional
    public void updateFcmToken(UUID driverId, String token) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setFcmToken(token);
        driverRepo.save(driver);
    }

    public StatsResponse getStats(UUID driverId) {
        DriverStats stats = statsRepo.findByDriverId(driverId)
                .orElse(DriverStats.builder().totalDeliveries(0).delivered(0).failed(0).cancelled(0).build());
        return StatsResponse.builder()
                .totalDeliveries(stats.getTotalDeliveries())
                .delivered(stats.getDelivered())
                .failed(stats.getFailed())
                .cancelled(stats.getCancelled())
                .build();
    }

    @Transactional
    public void toggleDuty(UUID driverId, boolean onDuty) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setActive(onDuty);
        driverRepo.save(driver);
    }

    @Transactional
    public DriverOnlineStatus updateAvailability(UUID driverId, DriverOnlineStatus newStatus) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        DriverOnlineStatus oldStatus = driver.getOnlineStatus();
        driver.setOnlineStatus(newStatus);
        driverRepo.save(driver);
        eventPublisher.publishStatusChanged(driverId, driver.getCompanyId(), oldStatus, newStatus, driver.getName());
        auditLogService.log(
                "DRIVER_AVAILABILITY_CHANGED",
                driverId,
                driver.getCompanyId(),
                driver.getName(),
                "DRIVER",
                String.format("{\"from\":\"%s\",\"to\":\"%s\"}", oldStatus != null ? oldStatus.name() : "OFFLINE", newStatus.name())
        );
        return newStatus;
    }

    public HistoryResponse getHistory(UUID driverId) {
        var items = historyRepo.findByDriverIdOrderByCreatedAtDesc(driverId).stream()
                .map(h -> HistoryResponse.HistoryItem.builder()
                        .deliveryId(h.getDeliveryId())
                        .status(h.getStatus())
                        .build())
                .collect(Collectors.toList());
        return HistoryResponse.builder().items(items).build();
    }

    private DriverProfileResponse mapToProfile(Driver d) {
        return DriverProfileResponse.builder()
                .id(d.getId().toString())
                .name(d.getName())
                .phone(d.getPhone())
                .active(d.getActive())
                .currentLat(d.getCurrentLat())
                .currentLng(d.getCurrentLng())
                .lastLocationAt(d.getLastLocationAt())
                .onlineStatus(d.getOnlineStatus() != null ? d.getOnlineStatus().name() : "OFFLINE")
                .build();
    }
}
