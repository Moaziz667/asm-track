package com.asm.driver.config;

import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverOnlineStatus;
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
    private final DriverAuditLogService auditLogService;
    private final DriverEventPublisher eventPublisher;

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void autoOfflineStaleDrivers() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(10);
        List<Driver> stale = driverRepo.findByOnlineStatusNotAndLastLocationAtBefore(
                DriverOnlineStatus.OFFLINE, threshold);

        for (Driver driver : stale) {
            DriverOnlineStatus previous = driver.getOnlineStatus();
            driver.setOnlineStatus(DriverOnlineStatus.OFFLINE);
            driverRepo.save(driver);

            eventPublisher.publishStatusChanged(
                    driver.getId(), driver.getCompanyId(),
                    previous, DriverOnlineStatus.OFFLINE,
                    driver.getName());

            auditLogService.log(
                    "DRIVER_AUTO_OFFLINED",
                    driver.getId(),
                    driver.getCompanyId(),
                    "SYSTEM",
                    "SYSTEM",
                    String.format("{\"previousStatus\":\"%s\",\"lastLocationAt\":\"%s\"}",
                            previous, driver.getLastLocationAt()));
        }

        if (!stale.isEmpty()) {
            log.info("Auto-offlined {} stale driver(s)", stale.size());
        }
    }
}
