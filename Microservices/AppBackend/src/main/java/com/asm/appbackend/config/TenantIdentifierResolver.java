package com.asm.appbackend.config;

import com.asm.appbackend.security.TenantContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Tells Hibernate which tenant (schema) the current unit of work belongs to, by reading the
 * {@link TenantContext} ThreadLocal set from the gateway-injected X-Company-Id header.
 *
 * <p>When no tenant is resolved (public endpoints, actuator, bootstrap) it falls back to
 * {@link TenantSchema#DEFAULT} = {@code public}. Hibernate forbids a null identifier.
 */
@Component
public class TenantIdentifierResolver
        implements CurrentTenantIdentifierResolver<String>, HibernatePropertiesCustomizer {

    @Override
    public String resolveCurrentTenantIdentifier() {
        UUID companyId = TenantContext.get();
        return companyId != null ? TenantSchema.schemaFor(companyId) : TenantSchema.DEFAULT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
