package com.asm.appbackend.config;

import com.asm.tenant.jpa.TenantIterator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies pending migrations to <b>every existing tenant schema</b> on startup.
 *
 * <p>Without this, moving the service onto Flyway would have fixed nothing that mattered. Spring
 * Boot's own Flyway run only touches the default {@code public} schema, and
 * {@link TenantSchemaProvisioner} only runs when a <i>new</i> tenant is created — so a migration
 * would reach neither the tenants already in the database. They would keep the schema they were born
 * with, exactly as under the old {@code schema.sql}, and the JPA entities would start expecting
 * columns those schemas do not have.
 *
 * <p>{@code provision} is idempotent — {@code CREATE SCHEMA IF NOT EXISTS} followed by
 * {@code flyway.migrate()} — so calling it on an existing schema applies whatever is pending and does
 * nothing when the schema is already current. It runs at the highest precedence so the schemas are up
 * to date before any scheduled job queries them.
 *
 * <p>Mirrors the runner {@code DeliveryMicroservice} has had; the two services now behave alike.
 */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
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
                // One broken tenant must not stop the others from being brought up to date.
                failed[0]++;
                log.error("Startup tenant migration failed for {}: {}", companyId, e.getMessage(), e);
            }
        });
        log.info("Startup tenant migration done: {} schema(s) up to date, {} failed", ok[0], failed[0]);
    }
}
