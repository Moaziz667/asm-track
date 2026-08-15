package com.asm.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Enterprise-grade RAG assistant for ASM Track.
 *
 * <p>A dedicated Assistant/AI service (ports &amp; adapters) that reuses the platform's Keycloak
 * resource-server auth, RBAC, tenant propagation, observability and resilience — it does not
 * re-implement any of them, and it never replaces the deterministic business services (SLA engine,
 * delivery/driver APIs). It grounds answers in an indexed knowledge corpus and cites its sources.
 */
@SpringBootApplication
@EnableFeignClients
public class AssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}
