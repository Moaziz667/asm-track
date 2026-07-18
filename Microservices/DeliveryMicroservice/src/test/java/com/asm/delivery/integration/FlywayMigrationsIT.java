package com.asm.delivery.integration;

import com.asm.delivery.config.SchemaMultiTenantConnectionProvider;
import com.asm.delivery.config.TenantIdentifierResolver;
import com.asm.delivery.config.TenantSchema;
import com.asm.delivery.config.TenantSchemaProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the whole Flyway migration set applies cleanly to a brand-new tenant schema. A tenant is
 * provisioned by replaying every migration from scratch (unlike {@code public}, which was migrated
 * incrementally over time), so a migration that only works "on top of the existing DB" — or that got
 * broken by an edit — would fail here and never reach production onboarding.
 *
 * <p>Only an integration test can catch this: it needs a real Postgres to actually run the DDL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMultiTenantConnectionProvider.class, TenantIdentifierResolver.class, TenantSchemaProvisioner.class})
class FlywayMigrationsIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("0c000000-0000-0000-0000-0000000000cc");
    private final String schema = TenantSchema.schemaFor(TENANT);

    @Autowired
    private TenantSchemaProvisioner provisioner;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void drop() {
        provisioner.deprovision(TENANT);
    }

    @Test
    void allMigrationsApplyCleanlyOnAFreshSchema() {
        // Provisioning runs Flyway.migrate() on the new schema — throws if any migration fails.
        provisioner.provision(TENANT);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // Every recorded migration must have succeeded (Flyway marks failures with success=false).
        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".flyway_schema_history WHERE success = false",
                Integer.class);
        assertThat(failures).as("no failed migrations").isZero();

        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(applied).as("migrations were actually applied").isGreaterThan(20);

        // Spot-check that the core domain tables landed in the tenant schema (not in public).
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, schema);
        assertThat(tables).contains("companies", "orders", "deliveries", "routes", "rma");
    }
}
