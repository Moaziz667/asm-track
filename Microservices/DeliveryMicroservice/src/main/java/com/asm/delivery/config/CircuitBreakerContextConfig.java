package com.asm.delivery.config;

import com.asm.delivery.security.TenantContext;
import org.slf4j.MDC;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * With {@code spring.cloud.openfeign.circuitbreaker.enabled=true} + a TimeLimiter, every Feign call
 * executes on the circuit breaker factory's OWN thread pool — not the calling thread. The Feign
 * {@code RequestInterceptor} (which stamps {@code X-Company-Id} from {@link TenantContext} /
 * the current request) therefore ran with empty ThreadLocals: outbound calls from AMQP consumers
 * and scheduled jobs silently lost the tenant, downstream hit the wrong (public) schema — and once
 * the tenant filters went fail-closed, got 403 "No tenant context".
 *
 * <p>This customizer replaces the factory's executor with one that captures TenantContext, MDC and
 * RequestContextHolder at submit time (still on the calling thread) and restores them around the
 * task, making tenant propagation across the breaker's thread hop structural.
 */
@Configuration
public class CircuitBreakerContextConfig {

    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> contextPropagatingCircuitBreakerCustomizer() {
        ExecutorService delegate = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "cb-ctx-exec");
            t.setDaemon(true);
            return t;
        });
        ExecutorService propagating = new ContextPropagatingExecutorService(delegate);
        return factory -> factory.configureExecutorService(propagating);
    }

    /** Delegating executor: {@code execute} runs on the SUBMITTING thread, so capture happens there. */
    static final class ContextPropagatingExecutorService extends AbstractExecutorService {
        private final ExecutorService delegate;

        ContextPropagatingExecutorService(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            final UUID tenant = TenantContext.get();
            final Map<String, String> mdc = MDC.getCopyOfContextMap();
            final RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            delegate.execute(() -> {
                if (tenant != null) TenantContext.set(tenant);
                if (mdc != null) MDC.setContextMap(mdc);
                if (attrs != null) RequestContextHolder.setRequestAttributes(attrs);
                try {
                    command.run();
                } finally {
                    TenantContext.clear();
                    MDC.clear();
                    RequestContextHolder.resetRequestAttributes();
                }
            });
        }

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
