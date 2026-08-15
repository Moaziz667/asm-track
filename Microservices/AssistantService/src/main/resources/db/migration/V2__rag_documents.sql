-- One row per ingested source. Authority + status + tenant_id are the guardrails that stop an
-- obsolete document from silently overriding a current authoritative one.
CREATE TABLE rag_document (
    id           UUID PRIMARY KEY,
    external_id  TEXT        NOT NULL,
    source_type  TEXT        NOT NULL,
    path         TEXT        NOT NULL,
    title        TEXT,
    authority    TEXT        NOT NULL,   -- AUTHORITATIVE | SECONDARY | LOW | EXCLUDED
    status       TEXT        NOT NULL,   -- current | deprecated | archived
    tenant_id    UUID        NOT NULL,   -- company UUID, or all-zero GLOBAL sentinel
    version      TEXT,
    checksum     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_rag_document_external UNIQUE (external_id)
);

CREATE INDEX ix_rag_document_tenant_authority ON rag_document (tenant_id, authority, status);
