-- Supersession history: each checksum-changing ingestion records a version so a retired document's
-- lineage is auditable and an obsolete version is never silently reused.
CREATE TABLE rag_document_version (
    id           UUID PRIMARY KEY,
    document_id  UUID        NOT NULL REFERENCES rag_document (id) ON DELETE CASCADE,
    version      TEXT        NOT NULL,
    checksum     TEXT        NOT NULL,
    status       TEXT        NOT NULL,   -- current | superseded
    ingested_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_rag_document_version_doc ON rag_document_version (document_id);
