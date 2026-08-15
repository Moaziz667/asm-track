-- Retrievable units. embedding is vector(1536) to match OpenAI text-embedding-3-small; changing the
-- embedding model means a new migration (new dimension) + re-embed, never an in-place edit.
CREATE TABLE rag_chunk (
    id                       UUID PRIMARY KEY,
    document_id              UUID        NOT NULL REFERENCES rag_document (id) ON DELETE CASCADE,
    tenant_id                UUID        NOT NULL,   -- denormalized for single-query filtering
    authority                TEXT        NOT NULL,
    section                  TEXT,
    ordinal                  INT         NOT NULL,
    content                  TEXT        NOT NULL,
    embedding                vector(1536),
    content_tsv              tsvector,
    embedding_model_version  TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Semantic search: HNSW on cosine distance (built once the corpus is populated in Phase 2/3).
CREATE INDEX ix_rag_chunk_embedding ON rag_chunk USING hnsw (embedding vector_cosine_ops);

-- Lexical search half of hybrid retrieval.
CREATE INDEX ix_rag_chunk_tsv ON rag_chunk USING gin (content_tsv);

-- Tenant + authority pre-filter, applied before either search.
CREATE INDEX ix_rag_chunk_tenant_authority ON rag_chunk (tenant_id, authority);
