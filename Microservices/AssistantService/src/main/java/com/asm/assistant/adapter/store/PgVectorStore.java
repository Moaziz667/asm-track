package com.asm.assistant.adapter.store;

import com.asm.assistant.domain.port.VectorStorePort;
import com.asm.assistant.ingestion.model.ParsedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * pgvector-backed store. Owns the corpus lifecycle:
 * <ul>
 *   <li>same checksum → UNCHANGED, no work (idempotent re-ingestion, no wasted embeddings);</li>
 *   <li>changed checksum → old chunks dropped, new chunks written, prior version marked superseded;</li>
 *   <li>new source → INSERTED.</li>
 * </ul>
 *
 * <p>The vector is written through {@code ?::vector} from a {@code [v1,v2,…]} literal, and the lexical
 * half of hybrid retrieval is populated in the same statement via {@code to_tsvector('french', …)}.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class PgVectorStore implements VectorStorePort {

    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public UpsertResult upsertDocument(String externalId, String sourceType, String path, String title,
                                       String authority, UUID tenantId, String checksum,
                                       List<ParsedChunk> chunks, List<float[]> embeddings,
                                       String embeddingModelVersion) {

        List<UUID> existing = jdbc.query(
                "SELECT id FROM rag_document WHERE external_id = ?",
                (rs, n) -> (UUID) rs.getObject("id"), externalId);

        boolean isNew = existing.isEmpty();
        UUID docId;

        if (!isNew) {
            docId = existing.get(0);
            String currentChecksum = jdbc.queryForObject(
                    "SELECT checksum FROM rag_document WHERE id = ?", String.class, docId);
            if (checksum.equals(currentChecksum)) {
                return UpsertResult.UNCHANGED;
            }
            // Superseded: retire old chunks + mark the previous version, then re-chunk.
            jdbc.update("DELETE FROM rag_chunk WHERE document_id = ?", docId);
            jdbc.update("UPDATE rag_document_version SET status = 'superseded' " +
                    "WHERE document_id = ? AND status = 'current'", docId);
            jdbc.update("UPDATE rag_document SET path = ?, title = ?, authority = ?, tenant_id = ?, " +
                    "status = 'current', version = ?, checksum = ?, updated_at = now() WHERE id = ?",
                    path, title, authority, tenantId, checksum, checksum, docId);
        } else {
            docId = UUID.randomUUID();
            jdbc.update("INSERT INTO rag_document " +
                    "(id, external_id, source_type, path, title, authority, status, tenant_id, version, checksum) " +
                    "VALUES (?,?,?,?,?,?, 'current', ?, ?, ?)",
                    docId, externalId, sourceType, path, title, authority, tenantId, checksum, checksum);
        }

        jdbc.update("INSERT INTO rag_document_version (id, document_id, version, checksum, status) " +
                "VALUES (?,?,?,?, 'current')", UUID.randomUUID(), docId, checksum, checksum);

        boolean haveVectors = embeddings != null && embeddings.size() == chunks.size();
        for (int i = 0; i < chunks.size(); i++) {
            ParsedChunk c = chunks.get(i);
            String vec = haveVectors ? toVectorLiteral(embeddings.get(i)) : null;
            jdbc.update("INSERT INTO rag_chunk " +
                    "(id, document_id, tenant_id, authority, section, ordinal, content, embedding, content_tsv, embedding_model_version) " +
                    "VALUES (?,?,?,?,?,?,?, ?::vector, to_tsvector('french', ?), ?)",
                    UUID.randomUUID(), docId, tenantId, authority, c.section(), c.ordinal(),
                    c.content(), vec, c.content(), haveVectors ? embeddingModelVersion : null);
        }
        return isNew ? UpsertResult.INSERTED : UpsertResult.UPDATED;
    }

    private String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
