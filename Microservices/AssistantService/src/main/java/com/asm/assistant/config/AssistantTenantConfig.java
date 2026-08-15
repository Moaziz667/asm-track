package com.asm.assistant.config;

import com.asm.tenant.TenantCoreConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Brings in tenant propagation (the {@code X-Company-Id} HTTP filter + AMQP post-processors + MDC)
 * from the shared module — and <b>only</b> that.
 *
 * <p>We deliberately do <b>not</b> import {@code TenantJpaConfig}: the assistant does not use
 * schema-per-tenant. Its corpus is mostly GLOBAL platform knowledge (architecture, ADRs, API,
 * business rules) shared by every tenant, plus some tenant-scoped documents. Duplicating the whole
 * corpus into one schema per tenant would be wasteful and wrong; instead every chunk carries a
 * {@code tenant_id} (a real company UUID, or the reserved GLOBAL sentinel) and retrieval filters
 * {@code tenant_id IN (:current, GLOBAL)} in the SQL itself. {@code TenantContext} still tells us who
 * the caller is, both for that filter and for the read-only live tools (Phase 4).
 */
@Configuration
@Import(TenantCoreConfig.class)
public class AssistantTenantConfig {
}
