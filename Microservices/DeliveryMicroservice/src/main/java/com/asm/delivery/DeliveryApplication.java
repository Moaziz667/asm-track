package com.asm.delivery;

import com.asm.tenant.TenantCoreConfig;
import com.asm.tenant.TenantJpaConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

// @EnableScheduling lives on SchedulingConfig (conditional on app.scheduling.enabled) so tests can turn
// the background jobs off — their external calls would otherwise leave non-daemon threads stuck.
//
// The tenant configurations are imported explicitly rather than picked up by component scanning: a
// service that reaches its data through a per-tenant schema should say so here, not acquire the
// behaviour as a side effect of a dependency.
@SpringBootApplication
@Import({TenantCoreConfig.class, TenantJpaConfig.class})
@EnableAsync
@EnableAspectJAutoProxy
@EnableTransactionManagement(order = 0)
@EnableSpringDataWebSupport(pageSerializationMode = EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO)
public class DeliveryApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeliveryApplication.class, args);
    }
}
