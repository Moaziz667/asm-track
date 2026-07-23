package com.asm.delivery.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration tests that need a real PostgreSQL (tenant isolation, Flyway migrations) — the
 * things a mock-based unit test physically cannot verify.
 *
 * <p>DB source, resolved once for the whole suite:
 * <ul>
 *   <li><b>default (CI):</b> a throwaway Testcontainers Postgres — self-contained, nothing to set up;</li>
 *   <li><b>{@code IT_DB_URL} set:</b> that existing instance instead — lets a developer run the suite
 *       against the already-running local stack without Docker-in-Docker. Tests only touch throwaway
 *       {@code company_*} schemas they create and drop, so this is safe against a live dev DB.</li>
 * </ul>
 *
 * <p>The container is a single static instance shared by every subclass (started once, not per test
 * class), so the suite pays the Postgres start-up cost only once.
 */
public abstract class AbstractPostgresIT {

    /** null when running against an external DB (IT_DB_URL) rather than Testcontainers. */
    protected static final PostgreSQLContainer<?> POSTGRES;

    static {
        if (System.getenv("IT_DB_URL") == null) {
            POSTGRES = new PostgreSQLContainer<>("postgres:16").withDatabaseName("it_db");
            POSTGRES.start();
        } else {
            POSTGRES = null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES != null) {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> System.getenv("IT_DB_URL"));
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("IT_DB_USER", "delivery"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("IT_DB_PASS", "delivery"));
        }
        // We provision tenant schemas explicitly per test; keep the app's own Flyway/DDL out of the way.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        // Multi-tenancy defers connection acquisition, so Hibernate can't auto-detect the dialect — pin it.
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}
