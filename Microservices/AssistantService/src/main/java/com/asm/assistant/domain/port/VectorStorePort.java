package com.asm.assistant.domain.port;

import com.asm.assistant.ingestion.model.ParsedChunk;

import java.util.List;
import java.util.UUID;

/**
 * Persists documents and their chunks/vectors, and enforces the corpus lifecycle: unchanged sources
 * are skipped (idempotent re-ingestion), changed ones supersede their previous version and re-chunk.
 */
public interface VectorStorePort {

    /**
     * Upsert one source and its chunks.
     *
     * @param embeddings one vector per chunk (same order), or {@code null} when embedding is disabled.
     * @return outcome describing whether work was done.
     */
    UpsertResult upsertDocument(String externalId, String sourceType, String path, String title,
                                String authority, UUID tenantId, String checksum,
                                List<ParsedChunk> chunks, List<float[]> embeddings,
                                String embeddingModelVersion);

    enum UpsertResult { INSERTED, UPDATED, UNCHANGED }
}
