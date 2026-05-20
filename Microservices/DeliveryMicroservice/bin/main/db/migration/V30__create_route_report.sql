CREATE TABLE IF NOT EXISTS route_report (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id        UUID NOT NULL UNIQUE REFERENCES routes(id) ON DELETE CASCADE,
    company_id      UUID NOT NULL,
    payload         JSONB NOT NULL,
    generated_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_route_report_company ON route_report(company_id);
