package com.asm.delivery.service;

import com.asm.delivery.dto.response.DriverDeliveryResponse;
import com.asm.delivery.dto.response.DriverProfileResponse;
import com.asm.delivery.dto.response.DriverStatsResponse;
import com.asm.delivery.entity.Delivery;
import com.asm.delivery.entity.DeliveryStatus;
import com.asm.delivery.entity.Driver;
import com.asm.delivery.exception.AppException;
import com.asm.delivery.repository.DeliveryRepository;
import com.asm.delivery.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DriverProfileService {

    private final DriverRepository     driverRepo;
    private final DeliveryRepository   deliveryRepo;
    private final DriverDeliveryService driverDeliveryService;
    private final PasswordEncoder      passwordEncoder;

    private static final List<DeliveryStatus> HISTORY_STATUSES = List.of(
            DeliveryStatus.DELIVERED,
            DeliveryStatus.FAILED,
            DeliveryStatus.CANCELLED
    );

    @Transactional(readOnly = true)
    public DriverProfileResponse getProfile(UUID driverId) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        return toResponse(driver);
    }

    @Transactional
    public DriverProfileResponse updateProfile(UUID driverId, String name, String city) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setName(name);
        if (city != null && !city.isBlank()) {
            driver.setCity(city);
        }
        return toResponse(driverRepo.save(driver));
    }

    @Transactional
    public void changePassword(UUID driverId, String currentPassword, String newPassword) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));

        if (!passwordEncoder.matches(currentPassword, driver.getPasswordHash())) {
            throw AppException.badRequest("Current password is incorrect");
        }

        driver.setPasswordHash(passwordEncoder.encode(newPassword));
        driverRepo.save(driver);
    }

    @Transactional
    public DriverProfileResponse updateAvailability(UUID driverId, boolean available) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        driver.setAvailable(available);
        return toResponse(driverRepo.save(driver));
    }

    @Transactional(readOnly = true)
    public List<DriverDeliveryResponse> getHistory(UUID driverId) {
        return deliveryRepo.findHistoryForDriver(driverId, HISTORY_STATUSES).stream()
                .map(driverDeliveryService::toDriverDeliveryResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DriverStatsResponse getStats(UUID driverId) {
        List<Delivery> history = deliveryRepo.findHistoryForDriver(driverId, HISTORY_STATUSES);
        long total     = history.size();
        long delivered = history.stream().filter(d -> d.getStatus() == DeliveryStatus.DELIVERED).count();
        long failed    = history.stream().filter(d -> d.getStatus() == DeliveryStatus.FAILED).count();
        long cancelled = history.stream().filter(d -> d.getStatus() == DeliveryStatus.CANCELLED).count();

        return new DriverStatsResponse(total, delivered, failed, cancelled);
    }

    private DriverProfileResponse toResponse(Driver driver) {
        return DriverProfileResponse.builder()
                .id(driver.getId())
                .name(driver.getName())
                .phone(driver.getPhone())
                .available(driver.getAvailable())
                .currentLat(driver.getCurrentLat())
                .currentLng(driver.getCurrentLng())
                .lastLocationAt(driver.getLastLocationAt())
                .createdAt(driver.getCreatedAt())
                .city(driver.getCity())
                .build();
    }
}
