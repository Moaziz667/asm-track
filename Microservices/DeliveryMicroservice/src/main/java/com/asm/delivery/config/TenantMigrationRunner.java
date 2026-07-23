package com.asm.delivery.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies pending Flyway migrations to <b>every existing tenant schema</b> on startup.
 *
 * <p>Spring Boot's Flyway only migrates the default {@code public} schema, and {@link TenantSchemaProvisioner}
 * only runs when a <i>new</i> tenant is provisioned — so without this, a schema-changing migration reaches
 * {@code public} but never the {@code company_*} schemas that already exist, and every such migration breaks
 * the live tenants at deploy time (the JPA entity expects the new column, the old schema still has the old one).
 *
 * <p>{@link TenantSchemaProvisioner#provision} is idempotent ({@code CREATE SCHEMA IF NOT EXISTS} +
 * {@code flyway.migrate()}), so calling it on an existing schema simply applies whatever migrations are pending
 * and records them — a no-op when the schema is already up to date. Runs early ({@code @Order} highest) so the
 * schemas are current before the scheduled tenant jobs start querying them.
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
                failed[0]++;
                log.error("Startup tenant migration failed for {}: {}", companyId, e.getMessage(), e);
            }
        });
        log.info("Startup tenant migration done: {} schema(s) up to date, {} failed", ok[0], failed[0]);
    }
}
