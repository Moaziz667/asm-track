package com.asm.driver.config;

import com.asm.tenant.TenantSchema;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema naming, pinned.
 *
 * <p>This function decides which PostgreSQL schema a request reads from, so it is the last line
 * between two customers' data. It also builds a SQL identifier, which is why the shape of its
 * output — and its refusal to return one for garbage input — is worth stating in a test rather
 * than trusting to a reading of the code.
 */
class TenantSchemaTest {

    private static final UUID ALPHA = UUID.fromString("54ed4906-3009-4a22-888e-8717f3d23178");

    @Test
    void buildsAnUnquotedIdentifierFromTheCompanyId() {
        assertThat(TenantSchema.schemaFor(ALPHA)).isEqualTo("company_54ed490630094a22888e8717f3d23178");
    }

    @Test
    void producesOnlyCharactersThatAreSafeUnquotedInSql() {
        // The name is interpolated into `SET search_path`, so the alphabet matters more than the
        // value. Restricted to [a-f0-9_], it cannot carry a quote or a semicolon whatever the input.
        for (int i = 0; i < 200; i++) {
            assertThat(TenantSchema.schemaFor(UUID.randomUUID())).matches("company_[0-9a-f]{32}");
        }
    }

    @Test
    void roundTripsBackToTheSameCompanyId() {
        assertThat(TenantSchema.companyIdFrom(TenantSchema.schemaFor(ALPHA))).isEqualTo(ALPHA);
    }

    @Test
    void returnsNothingForAnythingThatIsNotATenantSchema() {
        // The inverse runs over names read from the database, so it meets `public`, Flyway's own
        // tables and whatever else lives in the cluster. None of those are a tenant.
        assertThat(TenantSchema.companyIdFrom(TenantSchema.DEFAULT)).isNull();
        assertThat(TenantSchema.companyIdFrom(null)).isNull();
        assertThat(TenantSchema.companyIdFrom("information_schema")).isNull();
        assertThat(TenantSchema.companyIdFrom("company_")).isNull();
        assertThat(TenantSchema.companyIdFrom("company_tooshort")).isNull();
        assertThat(TenantSchema.companyIdFrom("company_" + "z".repeat(32))).isNull();
    }

    @Test
    void defaultsToPublicWhenNoTenantIsResolved() {
        assertThat(TenantSchema.DEFAULT).isEqualTo("public");
    }
}
