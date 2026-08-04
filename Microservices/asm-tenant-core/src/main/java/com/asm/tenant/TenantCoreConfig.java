package com.asm.tenant;

import com.asm.tenant.amqp.TenantInboundPostProcessor;
import com.asm.tenant.amqp.TenantMessagePostProcessor;
import com.asm.tenant.web.TenantContextFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Wires tenant propagation: HTTP in, AMQP in and out.
 *
 * <p>Imported explicitly by each service rather than auto-configured. That is deliberate — a service
 * that reaches its data through a tenant schema should say so in its own configuration, not acquire
 * the behaviour by adding a dependency. It also lets the ERP adapter take this part without the JPA
 * part, which it has no use for.
 *
 * <h2>The one setting</h2>
 * <pre>
 * asm:
 *   tenant:
 *     tenant-less-prefixes: /api/v1/auth/,/api/v1/public/
 * </pre>
 * Paths under these prefixes may arrive without an {@code X-Company-Id}; every other {@code /api/}
 * path is rejected without one. Omit the property entirely when every route is tenant-scoped.
 */
@Configuration
public class TenantCoreConfig {

    @Bean
    public FilterRegistrationBean<TenantContextFilter> tenantContextFilter(
            @Value("${asm.tenant.tenant-less-prefixes:}") List<String> tenantLessPrefixes) {
        FilterRegistrationBean<TenantContextFilter> registration =
                new FilterRegistrationBean<>(new TenantContextFilter(tenantLessPrefixes));
        // Ahead of anything that touches the database, and ahead of the security chain's own
        // ordering, so the tenant is established before any component can read data without one.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public TenantMessagePostProcessor tenantMessagePostProcessor() {
        return new TenantMessagePostProcessor();
    }

    @Bean
    public TenantInboundPostProcessor tenantInboundPostProcessor() {
        return new TenantInboundPostProcessor();
    }
}
