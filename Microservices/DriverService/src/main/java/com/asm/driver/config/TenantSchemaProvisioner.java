package com.asm.driver.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/**
 * Provisions a new tenant schema in the driver database: creates the schema and runs Flyway
 * migrations on it. Mirrors the delivery/appbackend provisioners so onboarding is uniform.
 */
@Slf4j
@Service
public class TenantSchemaProvisioner {

    private final DataSource dataSource;
    private final String migrationLocations;

    public TenantSchemaProvisioner(
            DataSource dataSource,
            @Value("${spring.flyway.locations:classpath:db/migration}") String migrationLocations) {
        this.dataSource = dataSource;
        this.migrationLocations = migrationLocations;
    }

    public void provision(UUID companyId) {
        String schemaName = TenantSchema.schemaFor(companyId);
        log.info("Provisioning tenant schema: {}", schemaName);

        boolean schemaCreated = false;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
            schemaCreated = true;
            log.info("Created schema: {}", schemaName);

            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schemaName)
                    .locations(new Location(migrationLocations))
                    .baselineOnMigrate(true)
                    .load();

            flyway.migrate();
            log.info("Flyway migrations completed for schema: {}", schemaName);

        } catch (Exception e) {
            log.error("Failed to provision tenant schema {}: {}", schemaName, e.getMessage(), e);
            if (schemaCreated) {
                try (Connection conn = dataSource.getConnection();
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
                    log.warn("Rolled back partially-provisioned schema: {}", schemaName);
                } catch (Exception rollbackEx) {
                    log.error("Rollback of schema {} failed — manual cleanup required: {}",
                            schemaName, rollbackEx.getMessage(), rollbackEx);
                }
            }
            throw new RuntimeException("Tenant provisioning failed for schema " + schemaName, e);
        }
    }

    public void deprovision(UUID companyId) {
        String schemaName = TenantSchema.schemaFor(companyId);
        log.warn("Deprovisioning tenant schema: {}", schemaName);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
            log.info("Dropped schema: {}", schemaName);
        } catch (Exception e) {
            log.error("Failed to deprovision tenant schema {}: {}", schemaName, e.getMessage(), e);
            throw new RuntimeException("Tenant deprovisioning failed for schema " + schemaName, e);
        }
    }
}
