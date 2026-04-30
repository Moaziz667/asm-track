-- Add target_entity column to categorize audit logs
ALTER TABLE audit_logs ADD COLUMN IF NOT EXISTS target_entity VARCHAR(50);
