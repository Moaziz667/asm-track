package com.asm.delivery.integration;

import com.asm.delivery.config.SchemaMultiTenantConnectionProvider;
import com.asm.delivery.config.TenantIdentifierResolver;
import com.asm.delivery.config.TenantSchema;
import com.asm.delivery.config.TenantSchemaProvisioner;
import com.asm.delivery.entity.Company;
import com.asm.delivery.repository.CompanyRepository;
import com.asm.delivery.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression guard for tenant isolation: proves that data written under company A is invisible under
 * company B (and vice-versa), routed purely by {@link TenantContext} through the real Hibernate
 * {@link SchemaMultiTenantConnectionProvider} + {@code SET search_path} against a real Postgres.
 *
 * <p>This can only be an integration test: a mock-based unit test would stub out the very
 * DataSource/connection that provides the isolation, so it would test the mock, not the isolation.
 * If a future change breaks schema routing (a mis-scoped query, a forgotten {@code TenantContext.set},
 * a connection-provider bug), this test fails the build.
 *
 * <p>Slice: {@link DataJpaTest} loads only the JPA layer (no web/RabbitMQ/MinIO to stand up), plus the
 * three multi-tenancy beans it needs, imported explicitly.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemaMultiTenantConnectionProvider.class, TenantIdentifierResolver.class, TenantSchemaProvisioner.class})
class TenantIsolationIT extends AbstractPostgresIT {

    // Deterministic, unmistakably-test UUIDs so the schemas can never collide with real tenants.
    private static final UUID TENANT_A = UUID.fromString("0a000000-0000-0000-0000-0000000000aa");
    private static final UUID TENANT_B = UUID.fromString("0b000000-0000-0000-0000-0000000000bb");

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private TenantSchemaProvisioner provisioner;

    @BeforeEach
    void provisionTenants() {
        // Clean slate, then a full Flyway-migrated schema per tenant.
        provisioner.deprovision(TENANT_A);
        provisioner.deprovision(TENANT_B);
        provisioner.provision(TENANT_A);
        provisioner.provision(TENANT_B);
    }

    @AfterEach
    void dropTenants() {
        TenantContext.clear();
        provisioner.deprovision(TENANT_A);
        provisioner.deprovision(TENANT_B);
    }

    /**
     * No surrounding transaction ({@code NOT_SUPPORTED}): each repository call opens its own transaction,
     * so it borrows a fresh connection whose {@code search_path} reflects the tenant set at that moment.
     * A single shared transaction would pin one connection/schema and quietly defeat the whole test.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void dataIsIsolatedPerTenant() {
        // Write one company under A.
        TenantContext.set(TENANT_A);
        companyRepository.saveAndFlush(Company.builder().name("A Corp").build());

        // Under B: A's row must be invisible; then write B's own row.
        TenantContext.set(TENANT_B);
        assertThat(companyRepository.findAll())
                .as("tenant B must not see tenant A's data")
                .isEmpty();
        companyRepository.saveAndFlush(Company.builder().name("B Corp").build());

        // Back to A: sees only A.
        TenantContext.set(TENANT_A);
        List<Company> a = companyRepository.findAll();
        assertThat(a).hasSize(1);
        assertThat(a.get(0).getName()).isEqualTo("A Corp");

        // B: sees only B.
        TenantContext.set(TENANT_B);
        List<Company> b = companyRepository.findAll();
        assertThat(b).hasSize(1);
        assertThat(b.get(0).getName()).isEqualTo("B Corp");
    }

    /** The schema-name derivation must round-trip, or the resolver and provisioner would disagree. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void schemaNameRoundTripsThroughUuid() {
        assertThat(TenantSchema.companyIdFrom(TenantSchema.schemaFor(TENANT_A))).isEqualTo(TENANT_A);
    }
}
