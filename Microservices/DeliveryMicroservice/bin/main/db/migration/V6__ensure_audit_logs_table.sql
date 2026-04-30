-- Ensure audit_logs table exists and has all required columns
-- This migration repairs any previous failures in schema initialization
CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_name VARCHAR(255) NOT NULL,
    actor_role VARCHAR(255) NOT NULL,
    action VARCHAR(255) NOT NULL,
    target_entity VARCHAR(50),
    resource_id TEXT,
    details TEXT,
    ip_address VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
