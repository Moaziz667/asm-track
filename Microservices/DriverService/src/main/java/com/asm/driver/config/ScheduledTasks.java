package com.asm.driver.config;

import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverOnlineStatus;
import com.asm.driver.repository.DriverInviteTokenRepository;
import com.asm.driver.repository.DriverRepository;
import com.asm.driver.service.DriverAuditLogService;
import com.asm.driver.service.DriverEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ScheduledTasks {

    private final DriverRepository driverRepo;
    private final DriverInviteTokenRepository inviteTokenRepo;
    private final DriverAuditLogService auditLogService;
    private final DriverEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void autoOfflineStaleDrivers() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);

        // Load before update so we can audit and publish events per driver
        List<Driver> stale = driverRepo.findByOnlineStatusNotAndLastLocationAtBefore(
                DriverOnlineStatus.OFFLINE, threshold);

        if (stale.isEmpty()) return;

        // Single bulk UPDATE instead of N individual saves
        int updated = driverRepo.bulkOfflineStaleDrivers(
                DriverOnlineStatus.OFFLINE, LocalDateTime.now(), threshold);

        for (Driver driver : stale) {
            DriverOnlineStatus previous = driver.getOnlineStatus();
            eventPublisher.publishStatusChanged(
                    driver.getId(), previous, DriverOnlineStatus.OFFLINE, driver.getName());
            auditLogService.log(
                    "DRIVER_AUTO_OFFLINED",
                    driver.getId(),
                    "SYSTEM", "SYSTEM",
                    String.format("{\"previousStatus\":\"%s\",\"lastLocationAt\":\"%s\"}",
                            previous, driver.getLastLocationAt()));
        }

        log.info("Auto-offlined {} stale driver(s)", updated);
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanupExpiredInviteTokens() {
        int deleted = inviteTokenRepo.deleteAllExpired(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired invite token(s)", deleted);
        }
    }
}
