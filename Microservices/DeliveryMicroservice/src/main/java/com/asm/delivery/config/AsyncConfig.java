package com.asm.delivery.config;

import com.asm.tenant.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.UUID;
import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Single-thread executor for address geocoding. Serializes Nominatim calls so a bulk
     * import of many BLs respects the public Nominatim usage policy (~1 request/second)
     * instead of firing a burst that gets the instance rate-limited or banned.
     *
     * <p>The {@link #tenantPropagatingDecorator() task decorator} carries the submitting request's
     * tenant onto the worker thread: geocoding loads and saves the order via JPA, which resolves the
     * schema from {@link TenantContext} — without it the async work would hit the {@code public} schema
     * and silently never persist coordinates for any real tenant.
     */
    @Bean(name = "geocodingExecutor")
    public Executor geocodingExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("geocode-");
        ex.setTaskDecorator(tenantPropagatingDecorator());
        ex.initialize();
        return ex;
    }

    /**
     * Shared executor for after-commit event publishing (STOMP broadcasts, FCM pushes, notification
     * persistence). Replaces bare {@code CompletableFuture.runAsync} on the ForkJoin COMMON pool:
     * common-pool threads carry no {@link TenantContext} (each caller had to remember a hand-rolled
     * wrapper — the exact omission that broke the ERP metadata cache), and event bursts competed
     * with every other common-pool user. The tenant decorator makes propagation structural.
     */
    @Bean(name = "eventExecutor")
    public Executor eventExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(5000);
        ex.setThreadNamePrefix("event-");
        ex.setTaskDecorator(tenantPropagatingDecorator());
        ex.initialize();
        return ex;
    }

    /** Captures the submitting thread's tenant and restores it around the async task. */
    private static TaskDecorator tenantPropagatingDecorator() {
        return runnable -> {
            final UUID tenant = TenantContext.get();
            return () -> {
                if (tenant != null) TenantContext.set(tenant);
                try {
                    runnable.run();
                } finally {
                    TenantContext.clear();
                }
            };
        };
    }
}
