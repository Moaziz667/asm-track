package com.asm.assistant.web;

import com.asm.assistant.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization regression for the answer surface (step 36): the assistant — and therefore its live
 * tools — is reachable only by authenticated operators. MOCK web env so the real security filter chain
 * runs; the tenant header is supplied so the fail-closed tenant filter isn't what rejects. Reuses the
 * deterministic stub embedder from {@link AbstractPostgresIT} to stay offline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AbstractPostgresIT.StubEmbeddingConfig.class)
class AuthorizationIT {

    private static PostgreSQLContainer<?> container;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
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
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost:0/certs");
    }

    private static String envOr(String key, String dflt) {
        String v = System.getenv(key);
        return v != null && !v.isBlank() ? v : dflt;
    }

    @Autowired MockMvc mvc;

    private static final String TENANT = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String BODY = "{\"query\":\"Quelles sont les phases du SLA ?\"}";

    @Test
    void anonymous_isUnauthorized() throws Exception {
        mvc.perform(post("/api/assistant/query")
                        .header("X-Company-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonOperatorRole_isForbidden() throws Exception {
        mvc.perform(post("/api/assistant/query")
                        .header("X-Company-Id", TENANT)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT")))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorRole_isAllowed() throws Exception {
        mvc.perform(post("/api/assistant/query")
                        .header("X-Company-Id", TENANT)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());   // empty corpus → grounded=false refusal, but authorized
    }
}
