package com.asm.driver.service;

import com.asm.driver.dto.request.LocationRequest;
import com.asm.driver.dto.response.DriverProfileResponse;
import com.asm.driver.dto.response.HistoryResponse;
import com.asm.driver.dto.response.StatsResponse;
import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverAccountStatus;
import com.asm.driver.entity.DriverOnlineStatus;
import com.asm.driver.entity.DriverStats;
import com.asm.driver.exception.AppException;
import com.asm.driver.repository.DriverHistoryRepository;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.repository.DriverStatsRepository;
import com.asm.driver.service.DriverAuditLogService;
import com.asm.driver.service.DriverEventPublisher;
import com.asm.driver.storage.AvatarService;
import lombok.RequiredArgsConstructor;
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
    private final DriverEventPublisher eventPublisher;
    private final DriverAuditLogService auditLogService;
    private final AvatarService avatarService;
    private final OutboxProcessor outboxProcessor;

    /**
     * Driver uploads/replaces their profile photo. The bytes are validated + re-encoded (EXIF stripped)
     * into square JPEG variants and stored; the version is bumped (cache-busting) and onboarding is
     * marked COMPLETE so the app's first-login photo gate is cleared.
     */
    @Transactional
    public DriverProfileResponse uploadPhoto(UUID driverId, byte[] data, String tokenName) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        int newVersion = (driver.getPhotoVersion() == null ? 0 : driver.getPhotoVersion()) + 1;
        String url = avatarService.store(driverId, data, newVersion);
        driver.setPhotoUrl(url);
        driver.setPhotoVersion(newVersion);
        driver.setPhotoStatus("READY");
        driver.setPhotoUpdatedAt(LocalDateTime.now());
        driver.setOnboardingStatus("COMPLETE");
        if (tokenName != null && !tokenName.isBlank() && !tokenName.equals(driver.getName())) {
            driver.setName(tokenName);
        }
        DriverProfileResponse res = mapToProfile(driverRepo.save(driver));
        // Mirror the avatar URL into Keycloak's `picture` attribute (→ OIDC picture claim) via the IAM
        // outbox (AppBackend is the sole KC owner). Best-effort: failure here never fails the upload.
        try {
            outboxProcessor.enqueue(OutboxProcessor.IAM_SET_PICTURE,
                    java.util.Map.of("appUserId", driverId.toString(), "picture", url));
        } catch (Exception e) {
            // outbox enqueue is transactional with this method; a failure rolls back — log only.
            org.slf4j.LoggerFactory.getLogger(DriverService.class)
                    .warn("Failed to enqueue IAM_SET_PICTURE for {}: {}", driverId, e.getMessage());
        }
        return res;
    }

    @Transactional
    public DriverProfileResponse getProfile(UUID driverId, String tokenName) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        // The display name is Keycloak-mastered (self-service in the account console). Refresh the
        // mirror from the (login) token's name claim so a self-edit reaches the app + attribution.
        if (tokenName != null && !tokenName.isBlank() && !tokenName.equals(driver.getName())) {
            driver.setName(tokenName);
            driver = driverRepo.save(driver);
        }
        return mapToProfile(driver);
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
    public DriverOnlineStatus updateAvailability(UUID driverId, DriverOnlineStatus newStatus) {
        Driver driver = driverRepo.findById(driverId)
                .orElseThrow(() -> AppException.notFound("Driver not found"));
        DriverOnlineStatus oldStatus = driver.getOnlineStatus();
        driver.setOnlineStatus(newStatus);
        driverRepo.save(driver);
        eventPublisher.publishStatusChanged(driverId, oldStatus, newStatus, driver.getName());
        auditLogService.log(
                "DRIVER_AVAILABILITY_CHANGED",
                driverId,
                driver.getName(),
                "DRIVER",
                String.format("{\"from\":\"%s\",\"to\":\"%s\"}", oldStatus != null ? oldStatus.name() : "OFFLINE", newStatus.name())
        );
        return newStatus;
    }

    public HistoryResponse getHistory(UUID driverId, org.springframework.data.domain.Pageable pageable) {
        var slice = historyRepo.findByDriverIdOrderByCreatedAtDesc(driverId, pageable);
        var items = slice.getContent().stream()
                .map(h -> HistoryResponse.HistoryItem.builder()
                        .deliveryId(h.getDeliveryId())
                        .status(h.getStatus())
                        .createdAt(h.getCreatedAt() != null ? h.getCreatedAt().toString() : null)
                        .build())
                .collect(Collectors.toList());
        return HistoryResponse.builder().items(items).hasNext(slice.hasNext()).build();
    }

    private DriverProfileResponse mapToProfile(Driver d) {
        // Regenerate photo URL at read time so MINIO_PUBLIC_URL changes take effect immediately.
        String photoUrl = null;
        if (d.getPhotoUrl() != null && d.getPhotoVersion() != null && d.getPhotoVersion() > 0) {
            photoUrl = avatarService.publicUrlFor(d.getId(), d.getPhotoVersion(), "thumb");
        }
        return DriverProfileResponse.builder()
                .id(d.getId().toString())
                .name(d.getName())
                .phone(d.getPhone())
                .active(d.getAccountStatus() == DriverAccountStatus.ACTIVE)
                .currentLat(d.getCurrentLat())
                .currentLng(d.getCurrentLng())
                .lastLocationAt(d.getLastLocationAt())
                .onlineStatus(d.getOnlineStatus() != null ? d.getOnlineStatus().name() : "OFFLINE")
                .photoUrl(photoUrl)
                .onboardingStatus(d.getOnboardingStatus())
                .build();
    }
}
