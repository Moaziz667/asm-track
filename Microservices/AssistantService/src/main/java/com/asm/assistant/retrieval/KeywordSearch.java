package com.asm.assistant.retrieval;

import com.asm.assistant.persistence.RagDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Lexical half of hybrid retrieval: Postgres full-text ({@code french} config) over {@code content_tsv}
 * with {@code ts_rank}. Catches exact terms, identifiers and codes that a dense embedding may blur
 * (e.g. "T4", "IN_TRANSIT", an endpoint path). Same tenant filter as {@link VectorSearch}.
 */
@Component
@RequiredArgsConstructor
public class KeywordSearch {

    private final JdbcTemplate jdbc;
    private final ChunkRowMapper mapper = new ChunkRowMapper();

    private static final String SQL = """
            SELECT c.id, c.document_id, d.external_id, d.path, c.section, c.authority, c.content,
                   ts_rank(c.content_tsv, plainto_tsquery('french', ?)) AS score
            FROM rag_chunk c
            JOIN rag_document d ON d.id = c.document_id
            WHERE c.content_tsv @@ plainto_tsquery('french', ?)
              AND d.status = 'current'
              AND c.tenant_id IN (?, ?)
            ORDER BY score DESC
            LIMIT ?
            """;

    public List<RetrievedChunk> search(String query, UUID tenantId, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return jdbc.query(SQL, mapper, query, query, tenantId, RagDocument.GLOBAL_TENANT, limit);
    }
}
