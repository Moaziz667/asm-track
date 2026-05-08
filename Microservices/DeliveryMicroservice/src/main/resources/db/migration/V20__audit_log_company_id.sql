ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS company_id UUID;
CREATE INDEX IF NOT EXISTS idx_audit_logs_company ON audit_logs(company_id);
