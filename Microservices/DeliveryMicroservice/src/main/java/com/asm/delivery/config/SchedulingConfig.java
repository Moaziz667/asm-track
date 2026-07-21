package com.asm.delivery.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the app's {@code @Scheduled} background jobs (outbox drain, SLA monitors, ERP auto-import,
 * cleanup, health snapshots). Split out of {@code DeliveryApplication} and gated on a property so that
 * integration tests can disable it: several of those jobs make outbound calls (RabbitMQ, ERP adapter),
 * and in an environment with no broker/ERP their retries can park a non-daemon scheduler thread — which
 * keeps the test JVM from exiting and hangs the build. Production leaves the property unset
 * ({@code matchIfMissing = true}), so scheduling stays on.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
