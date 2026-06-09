package com.asm.appbackend.scheduler;

import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class KeycloakSyncScheduler {

    private final AdminUserRepository adminUserRepo;
    private final KeycloakAdminClient keycloakAdminClient;

    // Runs every 5 minutes with initial delay of 10 seconds
    @Scheduled(fixedDelay = 300000, initialDelay = 10000)
    public void reconcileAdminUsers() {
        log.info("Starting Keycloak Admin Users synchronization and reconciliation...");
        List<AdminUser> dbUsers = adminUserRepo.findAll();

        for (AdminUser dbUser : dbUsers) {
            try {
                Map<String, Object> kcUser = keycloakAdminClient.getUserDetails(dbUser.getEmail());

                if (kcUser == null) {
                    // Provision user if missing in Keycloak
                    log.info("Sync: Admin user missing in Keycloak. Provisioning... email={}", dbUser.getEmail());
                    // Generate temporary random password
                    String randomPassword = "Tmp_" + UUID.randomUUID().toString().substring(0, 8) + "!";
                    keycloakAdminClient.createUser(
                            dbUser.getEmail(),
                            dbUser.getRole(),
                            dbUser.getId().toString(),
                            randomPassword
                    );
                } else {
                    // Reconcile status
                    boolean kcEnabled = Boolean.TRUE.equals(kcUser.get("enabled"));
                    if (kcEnabled != dbUser.isActive()) {
                        log.info("Sync: Status out of sync for user {}. DB active={}, Keycloak enabled={}. Reconciling...",
                                dbUser.getEmail(), dbUser.isActive(), kcEnabled);
                        keycloakAdminClient.setUserEnabled(dbUser.getId().toString(), dbUser.isActive());
                    }

                    // Reconcile role
                    String kcUserId = (String) kcUser.get("id");
                    List<String> kcRoles = keycloakAdminClient.getUserRoles(kcUserId);
                    if (!kcRoles.contains(dbUser.getRole().toUpperCase())) {
                        log.info("Sync: Role out of sync for user {}. DB role={}, Keycloak roles={}. Reconciling...",
                                dbUser.getEmail(), dbUser.getRole(), kcRoles);
                        keycloakAdminClient.setUserRole(dbUser.getId().toString(), dbUser.getEmail(), dbUser.getRole());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to reconcile user email={}: {}", dbUser.getEmail(), e.getMessage());
            }
        }
        log.info("Keycloak Admin Users reconciliation complete.");
    }
}
