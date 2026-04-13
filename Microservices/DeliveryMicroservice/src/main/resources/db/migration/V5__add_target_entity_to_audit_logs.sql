-- Add target_entity column to categorize audit logs
ALTER TABLE audit_logs ADD COLUMN target_entity VARCHAR(50);
