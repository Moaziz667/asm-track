package com.asm.tenant.jpa;

import com.asm.tenant.TenantContext;
import com.asm.tenant.TenantSchema;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;

import java.util.Map;
import java.util.UUID;

/**
 * Tells Hibernate which schema the current unit of work belongs to, by reading {@link TenantContext}.
 *
 * <p>When no tenant is resolved — actuator, bootstrap, a public endpoint whose tenant is established
 * further down — it falls back to {@link TenantSchema#DEFAULT}. Hibernate forbids a null identifier,
 * so the fallback is required rather than defensive.
 */
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
