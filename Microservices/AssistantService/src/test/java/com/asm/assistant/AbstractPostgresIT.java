package com.asm.assistant;

import com.asm.assistant.domain.port.EmbeddingPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

/**
 * Base for integration tests: a real Postgres <b>with pgvector</b> so migrations, the {@code vector}
 * type and the cosine operator all run exactly as in production. Flyway applies V1–V5 on startup.
 *
 * <p>Two ways to provide the database, so this runs anywhere:
 * <ul>
 *   <li>set {@code IT_DB_URL} (+ {@code IT_DB_USER}/{@code IT_DB_PASS}) to point at an already-running
 *       pgvector (the CI side-car pattern, and what we use locally to avoid Docker-in-Docker); or</li>
 *   <li>leave it unset and Testcontainers starts a {@code pgvector/pgvector:pg16} container.</li>
 * </ul>
 *
 * <p>Embedding is stubbed with a deterministic vector so tests never touch the network or Gemini
 * quota; retrieval SQL (tenant filter, hybrid ranking) is what's under test, not the embedder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(AbstractPostgresIT.StubEmbeddingConfig.class)
public abstract class AbstractPostgresIT {

    private static PostgreSQLContainer<?> container;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        String url = System.getenv("IT_DB_URL");
        if (url != null && !url.isBlank()) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> envOr("IT_DB_USER", "assistant"));
            r.add("spring.datasource.password", () -> envOr("IT_DB_PASS", "assistant"));
        } else {
            container = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
            container.start();
            r.add("spring.datasource.url", container::getJdbcUrl);
            r.add("spring.datasource.username", container::getUsername);
            r.add("spring.datasource.password", container::getPassword);
        }
        r.add("spring.flyway.baseline-on-migrate", () -> "true");
        r.add("assistant.ingestion.run-on-startup", () -> "false");
        // Keep the resource-server decoder from trying to reach Keycloak during context startup.
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost:0/certs");
    }

    private static String envOr(String key, String dflt) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : dflt;
    }

    /** Deterministic embedder: every query maps to a fixed unit vector — no network, no quota. */
    @TestConfiguration
    static class StubEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingPort stubEmbedding() {
            return new EmbeddingPort() {
                @Override public List<float[]> embed(List<String> texts) {
                    return texts.stream().map(t -> fixedVector()).toList();
                }
                @Override public float[] embedQuery(String text) { return fixedVector(); }
                @Override public String modelVersion() { return "stub:test"; }
                @Override public boolean isEnabled() { return true; }
            };
        }
    }

    /** A 1536-dim vector with a single non-zero component — matches the schema, deterministic. */
    protected static float[] fixedVector() {
        float[] v = new float[1536];
        v[0] = 1.0f;
        return v;
    }
}
