package com.asm.tenant;

import com.asm.tenant.jpa.SchemaMultiTenantConnectionProvider;
import com.asm.tenant.jpa.TenantIdentifierResolver;
import com.asm.tenant.jpa.TenantIterator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Wires schema-per-tenant routing into Hibernate.
 *
 * <p>Separate from {@link TenantCoreConfig} because not every service needs it: the ERP adapter
 * propagates a tenant through HTTP and AMQP but keeps its own small store, with no tenant schemas at
 * all. Bundling the two would have forced Hibernate multi-tenancy onto a service that has no use for
 * it — and made the shared module impossible to adopt there.
 */
@Configuration
public class TenantJpaConfig {

    @Bean
    public TenantIdentifierResolver tenantIdentifierResolver() {
        return new TenantIdentifierResolver();
    }

    @Bean
    public SchemaMultiTenantConnectionProvider schemaMultiTenantConnectionProvider(DataSource dataSource) {
        return new SchemaMultiTenantConnectionProvider(dataSource);
    }

    @Bean
    public TenantIterator tenantIterator(JdbcTemplate jdbcTemplate) {
        return new TenantIterator(jdbcTemplate);
    }
}
