-- Every assistant interaction is auditable: who asked what, which chunks grounded the answer, which
-- live tools ran, whether it refused. Retrieved content and tool I/O are stored as JSONB; secrets and
-- PII are redacted upstream (Phase 5) before anything lands here.
CREATE TABLE rag_audit (
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    user_id        TEXT,
    request_id     TEXT,
    question       TEXT        NOT NULL,
    route          TEXT,                  -- RAG | LIVE_API | DETERMINISTIC | COMBO | REFUSAL
    retrieved_ids  JSONB,
    citations      JSONB,
    tool_calls     JSONB,
    refused        BOOLEAN     NOT NULL DEFAULT false,
    latency_ms     INT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_rag_audit_tenant_time ON rag_audit (tenant_id, created_at DESC);
