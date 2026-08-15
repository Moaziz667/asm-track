package com.asm.assistant.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A retrievable unit of a {@link RagDocument}: a heading section, one OpenAPI operation, one code
 * card. Carries its own denormalized {@code tenant_id}/{@code authority} so a single retrieval query
 * can filter without a join.
 *
 * <p>The {@code embedding vector(1536)} and {@code content_tsv tsvector} columns exist in the table
 * (see {@code V3__rag_chunks.sql}) but are intentionally NOT mapped here: the embedding is written
 * and searched through native SQL / the pgvector operators in Phase 3, not through Hibernate.
 */
@Entity
@Table(name = "rag_chunk")
@Getter
@Setter
@NoArgsConstructor
public class RagChunk {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "authority", nullable = false)
    private String authority;

    /** Heading path / operationId — for citations. */
    @Column(name = "section")
    private String section;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "embedding_model_version")
    private String embeddingModelVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
