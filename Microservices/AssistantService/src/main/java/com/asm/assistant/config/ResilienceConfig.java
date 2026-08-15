package com.asm.assistant.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Circuit breakers for the external dependencies (LLM, live APIs), reusing the platform's resilience4j.
 * When a provider is repeatedly failing, the breaker opens and calls fast-fail into the degraded path
 * — no more hanging on timeouts, and (for the LLM) no more burning quota on a provider that's down.
 * Timeouts and retries live in the adapters; this adds the breaker on top.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                       // open once ≥50% of a window fails
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        return CircuitBreakerRegistry.of(config);
    }
}
