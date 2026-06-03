package com.asm.delivery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /**
     * Single-thread executor for address geocoding. Serializes Nominatim calls so a bulk
     * import of many BLs respects the public Nominatim usage policy (~1 request/second)
     * instead of firing a burst that gets the instance rate-limited or banned.
     */
    @Bean(name = "geocodingExecutor")
    public Executor geocodingExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(1);
        ex.setQueueCapacity(1000);
        ex.setThreadNamePrefix("geocode-");
        ex.initialize();
        return ex;
    }
}
