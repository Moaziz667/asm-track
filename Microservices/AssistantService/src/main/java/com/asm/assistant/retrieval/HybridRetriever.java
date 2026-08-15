package com.asm.assistant.retrieval;

import com.asm.assistant.domain.port.EmbeddingPort;
import com.asm.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Hybrid retrieval: run semantic and lexical search in parallel intent, then combine them with
 * Reciprocal Rank Fusion (RRF) — {@code score = Σ 1/(k + rank)} across the lists in which a chunk
 * appears. RRF needs no score calibration between the two very different scales (cosine vs ts_rank),
 * which is exactly why it's the pragmatic default here.
 *
 * <p>The tenant is taken from {@link TenantContext} (set by the gateway's {@code X-Company-Id});
 * both searches enforce it in SQL, so retrieval is tenant-safe before anything reaches the ranker.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridRetriever {

    private final EmbeddingPort embedding;
    private final VectorSearch vectorSearch;
    private final KeywordSearch keywordSearch;

    @Value("${assistant.retrieval.top-k:20}")
    private int topK;
    @Value("${assistant.retrieval.top-n:6}")
    private int topN;
    @Value("${assistant.retrieval.rrf-k:60}")
    private int rrfK;

    public List<RetrievedChunk> retrieve(String query) {
        UUID tenant = TenantContext.get();
        if (tenant == null) {
            // Should not happen behind the fail-closed tenant filter; refuse rather than read broadly.
            log.warn("Retrieval attempted with no tenant context — returning nothing");
            return List.of();
        }
        float[] qVec = embedding.embedQuery(query);
        List<RetrievedChunk> dense = vectorSearch.search(qVec, tenant, topK);
        List<RetrievedChunk> lexical = keywordSearch.search(query, tenant, topK);
        return fuse(dense, lexical);
    }

    private List<RetrievedChunk> fuse(List<RetrievedChunk> dense, List<RetrievedChunk> lexical) {
        Map<UUID, Double> scores = new HashMap<>();
        Map<UUID, RetrievedChunk> byId = new LinkedHashMap<>();
        accumulate(dense, scores, byId);
        accumulate(lexical, scores, byId);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topN)
                .map(e -> byId.get(e.getKey()).withFusedScore(e.getValue()))
                .toList();
    }

    private void accumulate(List<RetrievedChunk> list, Map<UUID, Double> scores, Map<UUID, RetrievedChunk> byId) {
        for (int rank = 0; rank < list.size(); rank++) {
            RetrievedChunk c = list.get(rank);
            scores.merge(c.chunkId(), 1.0 / (rrfK + rank + 1), Double::sum);
            byId.putIfAbsent(c.chunkId(), c);
        }
    }
}
