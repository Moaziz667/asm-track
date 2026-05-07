package com.asm.delivery.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enables the Hibernate companyFilter inside each @Transactional context.
 * Reads companyId from TenantContext (set by JwtAuthFilter per-request).
 * Order(LOWEST_PRECEDENCE - 1) keeps this inner to the transaction interceptor
 * when @EnableTransactionManagement(order = 0) is declared on the application class.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@Slf4j
public class TenantFilterAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Before("@within(org.springframework.transaction.annotation.Transactional) " +
            "|| @annotation(org.springframework.transaction.annotation.Transactional)")
    public void enableCompanyFilter() {
        String companyId = TenantContext.get();
        if (companyId == null || companyId.isBlank()) return; // super-admin — sees everything

        try {
            Session session = entityManager.unwrap(Session.class);
            if (session.getEnabledFilter("companyFilter") == null) {
                session.enableFilter("companyFilter")
                       .setParameter("companyId", UUID.fromString(companyId));
                log.debug("companyFilter enabled for companyId={}", companyId);
            }
        } catch (Exception e) {
            log.warn("Could not enable companyFilter: {}", e.getMessage());
        }
    }
}
