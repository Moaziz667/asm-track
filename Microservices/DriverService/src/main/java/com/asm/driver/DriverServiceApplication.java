package com.asm.driver;

import com.asm.tenant.TenantCoreConfig;
import com.asm.tenant.TenantJpaConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The tenant configurations are imported explicitly rather than picked up by component scanning: a
 * service that reaches its data through a per-tenant schema should say so here, not acquire the
 * behaviour as a side effect of a dependency.
 */
@SpringBootApplication
@EnableScheduling
@Import({TenantCoreConfig.class, TenantJpaConfig.class})
public class DriverServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DriverServiceApplication.class, args);
    }
}
