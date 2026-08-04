package com.asm.erpadapter;

import com.asm.tenant.TenantCoreConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Imports {@link TenantCoreConfig} alone — not {@code TenantJpaConfig}. This service propagates a
 * tenant through HTTP and AMQP so it can resolve the right ERP credentials, but keeps its own small
 * H2 store with no tenant schemas at all. That asymmetry is precisely why the shared module ships
 * the two configurations separately instead of one.
 */
@SpringBootApplication
@Import(TenantCoreConfig.class)
@EnableScheduling // V2 — for the Odoo→ASM change-polling fallback (ErpChangePoller)
public class ErpAdapterApplication {
    public static void main(String[] args) {
        SpringApplication.run(ErpAdapterApplication.class, args);
    }
}
