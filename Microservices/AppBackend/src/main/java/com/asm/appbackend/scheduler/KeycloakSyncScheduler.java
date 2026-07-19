package com.asm.appbackend.scheduler;

import com.asm.appbackend.client.KeycloakAdminClient;
import com.asm.appbackend.entity.AdminUser;
import com.asm.appbackend.repository.AdminUserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reconciles the {@code admin_users} table against its Keycloak mirror. Two passes:
 * <ul>
 *   <li><b>dirty</b> (every 60s): only rows flagged {@code kcSynced=false} — fast healing of
 *       just-created/updated users so attribution-critical fields (incl. display name) propagate
 *       quickly, without re-scanning every user every tick.</li>
 *   <li><b>full</b> (every 15min): drift audit over all rows to catch out-of-band Keycloak changes.</li>
 * </ul>
 * Both emit Micrometer metrics ({@code kc.sync.*}) so a permanently-failing sync is visible instead
 * of buried in logs. The reconciler — not a server-minted password — is the consistency mechanism;
 * a missing user is re-provisioned with an {@code UPDATE_PASSWORD} required action.
 */
@Component
@Slf4j
public class KeycloakSyncScheduler {

    private final AdminUserRepository adminUserRepo;
    private final KeycloakAdminClient keycloakAdminClient;
    private final MeterRegistry meters;
    private final com.asm.appbackend.config.TenantIterator tenantIterator;
    private final AtomicLong lastSuccessEpoch = new AtomicLong(0);

    public KeycloakSyncScheduler(AdminUserRepository adminUserRepo,
                                 KeycloakAdminClient keycloakAdminClient,
                                 MeterRegistry meters,
                                 com.asm.appbackend.config.TenantIterator tenantIterator) {
        this.adminUserRepo = adminUserRepo;
        this.keycloakAdminClient = keycloakAdminClient;
        this.meters = meters;
        this.tenantIterator = tenantIterator;
        meters.gauge("kc.sync.last_success.seconds", lastSuccessEpoch);
    }

    /**
     * Fast incremental pass: only rows that may diverge (just created/updated, or failed sync).
     * The {@code admin_users} table is per-tenant, so we iterate every provisioned tenant — otherwise
     * the scheduled thread (no TenantContext) would only scan the empty {@code public} schema and no
     * real tenant's admins would ever reconcile to Keycloak.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void reconcileDirty() {
        tenantIterator.forEachActive(companyId -> {
            List<AdminUser> dirty = adminUserRepo.findByKcSyncedFalse();
            if (dirty.isEmpty()) return;
            log.info("Keycloak admin sync (dirty pass): {} row(s) to reconcile for tenant {}", dirty.size(), companyId);
            runPass("dirty", dirty);
        });
    }

    /** Full drift audit over all rows — catches out-of-band Keycloak edits the dirty pass misses. */
    @Scheduled(fixedDelay = 900_000, initialDelay = 60_000)
    public void reconcileAll() {
        tenantIterator.forEachActive(companyId -> {
            log.info("Keycloak admin sync (full pass): starting drift audit for tenant {}...", companyId);
            runPass("full", adminUserRepo.findAll());
            log.info("Keycloak admin sync (full pass): complete for tenant {}.", companyId);
        });
    }

    private void runPass(String pass, List<AdminUser> users) {
        meters.counter("kc.sync.runs", "service", "admin", "pass", pass).increment();
        for (AdminUser dbUser : users) {
            try {
                reconcileOne(dbUser);
            } catch (Exception e) {
                meters.counter("kc.sync.failures", "service", "admin", "pass", pass).increment();
                log.error("Failed to reconcile admin user email={}: {}", dbUser.getEmail(), e.getMessage());
            }
        }
        lastSuccessEpoch.set(System.currentTimeMillis() / 1000);
    }

    /** Reconcile a single user; flips kcSynced=true only when Keycloak is confirmed in sync. */
    private void reconcileOne(AdminUser dbUser) {
        Map<String, Object> kcUser = keycloakAdminClient.getUserDetails(dbUser.getEmail());

        if (kcUser == null) {
            // Missing in Keycloak — provision with no server-side password (UPDATE_PASSWORD required
            // action). createUser is idempotent and also applies the display name on the way through.
            log.info("Sync: admin user missing in Keycloak. Provisioning... email={}", dbUser.getEmail());
            keycloakAdminClient.createUser(dbUser.getEmail(), dbUser.getRole(), dbUser.getId().toString(), null, dbUser.getName());
            meters.counter("kc.sync.repairs", "service", "admin", "kind", "provision").increment();
            // Best-effort: prompt the user to set a password (no-op if SMTP isn't configured).
            try {
                keycloakAdminClient.triggerPasswordResetEmail(dbUser.getId().toString());
            } catch (Exception e) {
                log.warn("Provisioned admin {} but could not send password-reset email: {}", dbUser.getEmail(), e.getMessage());
            }
            markSynced(dbUser);
            return;
        }

        boolean repaired = false;

        // Status drift
        boolean kcEnabled = Boolean.TRUE.equals(kcUser.get("enabled"));
        if (kcEnabled != dbUser.isActive()) {
            log.info("Sync: status drift for {} (db={}, kc={}). Reconciling...", dbUser.getEmail(), dbUser.isActive(), kcEnabled);
            keycloakAdminClient.setUserEnabled(dbUser.getId().toString(), dbUser.isActive());
            repaired = true;
        }

        // Role drift
        String kcUserId = (String) kcUser.get("id");
        List<String> kcRoles = keycloakAdminClient.getUserRoles(kcUserId);
        if (!kcRoles.contains(dbUser.getRole().toUpperCase())) {
            log.info("Sync: role drift for {} (db={}, kc={}). Reconciling...", dbUser.getEmail(), dbUser.getRole(), kcRoles);
            keycloakAdminClient.setUserRole(dbUser.getId().toString(), dbUser.getEmail(), dbUser.getRole());
            repaired = true;
        }

        // NOTE: the display name is intentionally NOT reconciled DB→KC. The name is Keycloak-mastered
        // (users self-edit it in the account console); pushing the DB value here would revert their
        // edit. The mirror is refreshed KC→DB on login (POST /api/admin/me/sync). Only role/status,
        // which the app masters, are reconciled DB→KC above.

        if (repaired) {
            meters.counter("kc.sync.drift", "service", "admin").increment();
            meters.counter("kc.sync.repairs", "service", "admin", "kind", "attributes").increment();
        }
        markSynced(dbUser);
    }

    private void markSynced(AdminUser dbUser) {
        if (!dbUser.isKcSynced()) {
            dbUser.setKcSynced(true);
            adminUserRepo.save(dbUser);
        }
    }
}
