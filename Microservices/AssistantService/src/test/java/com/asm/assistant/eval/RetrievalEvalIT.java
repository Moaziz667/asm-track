package com.asm.assistant.eval;

import com.asm.assistant.ingestion.IngestionService;
import com.asm.assistant.retrieval.HybridRetriever;
import com.asm.assistant.retrieval.RetrievedChunk;
import com.asm.tenant.TenantContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live retrieval quality gate (steps 32–33). Opt-in — it embeds the real corpus via Gemini, so it is
 * NOT part of CI (quota + non-determinism). Run it deliberately:
 *
 * <pre>
 *   RUN_LIVE_EVAL=1 GEMINI_API_KEY=… EVAL_CORPUS_DIR=/path/to/docs \
 *   IT_DB_URL=jdbc:postgresql://…/assistant_db gradle integrationTest --tests '*RetrievalEvalIT'
 * </pre>
 *
 * It ingests {@code EVAL_CORPUS_DIR}, runs every RAG golden question, and reports Recall@5 / MRR /
 * NDCG@10 against the expected sources, asserting realistic thresholds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_EVAL", matches = "1")
class RetrievalEvalIT {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("IT_DB_URL"));
        r.add("spring.datasource.username", () -> envOr("IT_DB_USER", "assistant"));
        r.add("spring.datasource.password", () -> envOr("IT_DB_PASS", "assistant"));
        r.add("spring.flyway.baseline-on-migrate", () -> "true");
        r.add("assistant.ingestion.root", () -> System.getenv("EVAL_CORPUS_DIR"));
        r.add("assistant.ingestion.run-on-startup", () -> "false");
        r.add("assistant.embedding.provider", () -> "gemini");
        r.add("assistant.embedding.api-key", () -> envOr("GEMINI_API_KEY", ""));
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost:0/certs");
    }

    private static String envOr(String k, String d) {
        String v = System.getenv(k);
        return v != null && !v.isBlank() ? v : d;
    }

    @Autowired IngestionService ingestion;
    @Autowired HybridRetriever retriever;

    static boolean ingested = false;

    @BeforeAll
    static void note() { /* ingestion happens in the test (needs the injected bean) */ }

    @Test
    @SuppressWarnings("unchecked")
    void retrievalQuality_meetsThresholds() {
        if (!ingested) { ingestion.ingestAll(); ingested = true; }
        TenantContext.set(TENANT);

        List<Map<String, Object>> golden = loadGolden();
        List<Double> recalls = new ArrayList<>();
        List<Double> rrs = new ArrayList<>();
        List<Double> ndcgs = new ArrayList<>();

        for (Map<String, Object> q : golden) {
            if (!"RAG".equals(q.get("route"))) continue;
            List<String> expect = (List<String>) q.getOrDefault("expect_paths", List.of());
            if (expect.isEmpty()) continue;

            List<RetrievedChunk> ranked = retriever.retrieve((String) q.get("q"));
            List<Boolean> rel = ranked.stream()
                    .map(c -> expect.stream().anyMatch(e -> c.externalId().contains(e)))
                    .toList();

            recalls.add(EvalMetrics.hitAtK(rel, 5));
            rrs.add(EvalMetrics.reciprocalRank(rel));
            ndcgs.add(EvalMetrics.ndcgAtK(rel, 10));
        }
        TenantContext.clear();

        double recallAt5 = EvalMetrics.mean(recalls);
        double mrr = EvalMetrics.mean(rrs);
        double ndcg = EvalMetrics.mean(ndcgs);
        System.out.printf("EVAL over %d RAG questions → Recall@5=%.3f  MRR=%.3f  NDCG@10=%.3f%n",
                recalls.size(), recallAt5, mrr, ndcg);

        assertThat(recallAt5).as("Recall@5").isGreaterThanOrEqualTo(0.80);
        assertThat(mrr).as("MRR").isGreaterThanOrEqualTo(0.65);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> loadGolden() {
        try (InputStream in = getClass().getResourceAsStream("/eval/golden-questions.yaml")) {
            Map<String, Object> root = new Yaml().load(in);
            return (List<Map<String, Object>>) root.get("questions");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load golden set", e);
        }
    }
}
