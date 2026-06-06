package com.asm.driver.scheduler;

import com.asm.driver.client.KeycloakAdminClient;
import com.asm.driver.entity.Driver;
import com.asm.driver.entity.DriverAccountStatus;
import com.asm.driver.repository.DriverRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakDriverSyncScheduler {

    private final DriverRepository driverRepo;
    private final KeycloakAdminClient keycloakAdminClient;

    // Runs every 5 minutes with initial delay of 20 seconds
    @Scheduled(fixedDelay = 300000, initialDelay = 20000)
    public void reconcileDrivers() {
        log.info("Starting Keycloak Drivers synchronization and reconciliation...");
        List<Driver> drivers = driverRepo.findAll();

        for (Driver driver : drivers) {
            String appUserId = driver.getId().toString();
            try {
                Map<String, Object> kcUser = keycloakAdminClient.getUserDetails(appUserId);

                if (kcUser == null) {
                    // Provision driver if missing in Keycloak
                    log.info("Sync: Driver missing in Keycloak. Provisioning... id={}, email={}", appUserId, driver.getEmail());
                    keycloakAdminClient.createDriver(appUserId, driver.getEmail(), driver.getPhone());

                    // If driver is suspended in DB, ensure they are disabled in Keycloak
                    boolean isDbActive = driver.getIsRegistered() && driver.getAccountStatus() == DriverAccountStatus.ACTIVE;
                    if (!isDbActive) {
                        keycloakAdminClient.setUserEnabled(appUserId, false);
                    }
                } else {
                    // Reconcile status: Keycloak enabled should match active operational status
                    boolean isDbActive = driver.getIsRegistered() && driver.getAccountStatus() == DriverAccountStatus.ACTIVE;
                    boolean kcEnabled = Boolean.TRUE.equals(kcUser.get("enabled"));

                    if (kcEnabled != isDbActive) {
                        log.info("Sync: Status out of sync for driver ID={}. DB active={}, Keycloak enabled={}. Reconciling...",
                                appUserId, isDbActive, kcEnabled);
                        keycloakAdminClient.setUserEnabled(appUserId, isDbActive);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to reconcile driver ID={} (email={}): {}", appUserId, driver.getEmail(), e.getMessage());
            }
        }
        log.info("Keycloak Drivers reconciliation complete.");
    }
}
