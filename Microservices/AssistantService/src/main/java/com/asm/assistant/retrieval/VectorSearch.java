package com.asm.assistant.retrieval;

import com.asm.assistant.persistence.RagDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Semantic search over pgvector (cosine distance, HNSW index). The tenant filter is part of the
 * query itself — {@code tenant_id IN (current, GLOBAL)} — so nothing outside the caller's tenant (or
 * the shared platform corpus) can ever reach the ranker, let alone the LLM. Only {@code current}
 * documents with a populated embedding are considered.
 */
@Component
@RequiredArgsConstructor
public class VectorSearch {

    private final JdbcTemplate jdbc;
    private final ChunkRowMapper mapper = new ChunkRowMapper();

    private static final String SQL = """
            SELECT c.id, c.document_id, d.external_id, d.path, c.section, c.authority, c.content,
                   1 - (c.embedding <=> ?::vector) AS score
            FROM rag_chunk c
            JOIN rag_document d ON d.id = c.document_id
            WHERE c.embedding IS NOT NULL
              AND d.status = 'current'
              AND c.tenant_id IN (?, ?)
            ORDER BY c.embedding <=> ?::vector
            LIMIT ?
            """;

    public List<RetrievedChunk> search(float[] queryVector, UUID tenantId, int limit) {
        if (queryVector == null) return List.of();
        String vec = toVectorLiteral(queryVector);
        return jdbc.query(SQL, mapper, vec, tenantId, RagDocument.GLOBAL_TENANT, vec, limit);
    }

    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
