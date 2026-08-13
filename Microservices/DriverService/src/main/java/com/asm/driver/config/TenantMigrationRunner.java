package com.asm.driver.config;

import com.asm.tenant.jpa.TenantIterator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies pending Flyway migrations to <b>every existing tenant schema</b> on startup.
 *
 * <p>Spring Boot's Flyway migrates only the default {@code public} schema, and
 * {@link TenantSchemaProvisioner} runs only when a <i>new</i> tenant is created. This service had
 * neither piece in between, so a migration reached {@code public} and never the {@code company_*}
 * schemas already in use — which is exactly what adding {@code last_seen_at} did: the entity
 * expected the column, six live schemas did not have it, and every read of a driver failed with
 * "column d1_0.last_seen_at does not exist". DeliveryService has carried this runner for a while;
 * DriverService was one migration away from needing it.
 *
 * <p>{@code provision} is idempotent — {@code CREATE SCHEMA IF NOT EXISTS} followed by
 * {@code flyway.migrate()} — so calling it on an existing schema applies what is pending and does
 * nothing when there is nothing to do. Ordered first, so the schemas are current before the
 * scheduled tenant jobs start querying them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class TenantMigrationRunner implements ApplicationRunner {

    private final TenantIterator tenantIterator;
    private final TenantSchemaProvisioner provisioner;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Startup: applying pending migrations to all existing tenant schemas…");
        int[] ok = {0};
        int[] failed = {0};
        tenantIterator.forEachActive(companyId -> {
            try {
                provisioner.provision(companyId);
                ok[0]++;
            } catch (Exception e) {
                failed[0]++;
                log.error("Startup tenant migration failed for {}: {}", companyId, e.getMessage(), e);
            }
        });
        log.info("Startup tenant migration done: {} schema(s) up to date, {} failed", ok[0], failed[0]);
    }
}
