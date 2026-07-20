package com.asm.appbackend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/**
 * Provisions a new tenant schema in the app_backend database.
 * Creates the schema and runs schema.sql against it.
 */
@Slf4j
@Service
public class TenantSchemaProvisioner {

    private final DataSource dataSource;

    public TenantSchemaProvisioner(DataSource dataSource) {
        this.dataSource = dataSource;
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

            // Run schema.sql against the tenant schema
            ClassPathResource schemaResource = new ClassPathResource("schema.sql");
            String sql = schemaResource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

            conn.createStatement().execute("SET search_path TO \"" + schemaName + "\"");

            // Split and execute each statement
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    try {
                        conn.createStatement().execute(trimmed);
                    } catch (Exception e) {
                        // Continue on non-fatal errors (e.g., ALTER TABLE on column that exists)
                        log.debug("Statement skipped (may already exist): {}", e.getMessage());
                    }
                }
            }

            // Seed system_settings singleton so the tenant has a default ERP config row.
            conn.createStatement().execute(
                "INSERT INTO system_settings (id, active_erp_provider, connection_status) "
                + "VALUES ('SINGLETON', 'ODOO', 'NOT_CONFIGURED') ON CONFLICT (id) DO NOTHING");

            log.info("Schema provisioning completed for: {}", schemaName);

        } catch (Exception e) {
            log.error("Failed to provision tenant schema {}: {}", schemaName, e.getMessage(), e);
            // Rollback: never leave a half-provisioned schema behind. Only drop what we just created.
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
