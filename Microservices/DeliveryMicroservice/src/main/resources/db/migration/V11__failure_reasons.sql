-- =============================================================================
-- Failure reasons catalog (driver-facing motifs that roll up to FailureCode).
-- The entity + controller existed but no migration created the table; this adds
-- it and seeds a starter set for the default company.
-- =============================================================================
CREATE TABLE IF NOT EXISTS failure_reasons (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id  UUID         NOT NULL,
    code        VARCHAR(60)  NOT NULL,
    label       VARCHAR(160) NOT NULL,
    category    VARCHAR(30)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_failure_reasons_company_code UNIQUE (company_id, code)
);
CREATE INDEX IF NOT EXISTS idx_failure_reasons_company_active
    ON failure_reasons(company_id, active);

-- No default seed: multi-tenant. Failure reasons are seeded explicitly per tenant at onboarding.
