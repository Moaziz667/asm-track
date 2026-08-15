package com.asm.assistant.retrieval;

import java.util.UUID;

/**
 * One retrieved chunk plus the metadata needed for grounding and citations. {@code score} is the
 * per-strategy raw score (cosine similarity or ts_rank) before fusion; {@code fusedScore} is the RRF
 * score after combining strategies.
 */
public record RetrievedChunk(
        UUID chunkId,
        UUID documentId,
        String externalId,
        String path,
        String section,
        String authority,
        String content,
        double score,
        double fusedScore
) {
    public RetrievedChunk withFusedScore(double fused) {
        return new RetrievedChunk(chunkId, documentId, externalId, path, section, authority, content, score, fused);
    }
}
