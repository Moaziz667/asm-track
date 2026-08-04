package com.asm.appbackend.config;

import com.asm.tenant.TenantSchema;

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
 * Provisions a tenant schema in the app_backend database: creates it, then runs Flyway on it.
 *
 * <h2>What this replaced</h2>
 * The previous version read {@code schema.sql}, split it on {@code ";"} and executed the pieces,
 * logging every failure at debug level and continuing. Three problems came with that:
 *
 * <ul>
 *   <li><b>No version tracking.</b> Editing the file only affected tenants provisioned afterwards.
 *       Existing ones never received the change and diverged silently, with nothing recording that
 *       they had.</li>
 *   <li><b>Failures were invisible.</b> A {@code CREATE TABLE} that did not run left the tenant with
 *       a schema missing a table, and provisioning reported success.</li>
 *   <li><b>Splitting on {@code ";"} is not parsing.</b> It breaks on the first semicolon inside a
 *       string literal or a function body.</li>
 * </ul>
 *
 * <p>Flyway answers all three, and it is what {@code DeliveryMicroservice} already did — the two
 * services now provision the same way rather than each having its own.
 *
 * <p>{@code baselineOnMigrate} lets an already-populated schema be adopted instead of rejected: the
 * tenants that exist keep their tables and are recorded at the baseline version, while a fresh schema
 * runs every migration from V1.
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

    /**
     * Creates a tenant schema and applies all migrations to it.
     *
     * @param companyId the company UUID (becomes the schema name: {@code company_<32hex>})
     */
    public void provision(UUID companyId) {
        String schemaName = TenantSchema.schemaFor(companyId);
        log.info("Provisioning tenant schema: {}", schemaName);

        boolean schemaCreated = false;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Idempotent — company_<32hex> is a valid unquoted identifier (see TenantSchema).
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

            // The tenant's ERP configuration is a singleton row the application expects to exist.
            // Seeded here rather than in a migration: a migration that inserts rows would re-run on
            // every new tenant schema anyway, and this keeps the schema definition free of data.
            try (Statement seed = conn.createStatement()) {
                seed.execute("SET search_path TO \"" + schemaName + "\"");
                seed.execute("INSERT INTO system_settings (id, active_erp_provider, connection_status) "
                        + "VALUES ('SINGLETON', 'ODOO', 'NOT_CONFIGURED') ON CONFLICT (id) DO NOTHING");
            }

        } catch (Exception e) {
            log.error("Failed to provision tenant schema {}: {}", schemaName, e.getMessage(), e);
            // Never leave a half-provisioned schema behind. Only drop what we just created.
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

    /** Drops a tenant schema. Use with caution — only for deprovisioning. */
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
