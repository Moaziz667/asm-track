CREATE TABLE IF NOT EXISTS idempotent_transaction (
    transaction_id VARCHAR(255) PRIMARY KEY,
    tenant_id UUID,
    erp_order_id VARCHAR(255),
    status VARCHAR(20),
    response_payload TEXT,
    processed_at TIMESTAMP
);

-- Upgrade path for pre-multi-tenant H2 files created before tenant_id existed.
ALTER TABLE idempotent_transaction ADD COLUMN IF NOT EXISTS tenant_id UUID;

CREATE TABLE IF NOT EXISTS erp_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id UUID NOT NULL,
    capability VARCHAR(64) NOT NULL,
    mapping_type VARCHAR(16) NOT NULL,
    odoo_name VARCHAR(128) NOT NULL,
    target_model VARCHAR(128) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, capability)
);

-- Durable inbound-poll cursor per tenant: an in-memory cursor re-seeded at "now" on every restart
-- permanently skipped any ERP change made while the adapter was down.
CREATE TABLE IF NOT EXISTS erp_poll_cursor (
    tenant_id UUID PRIMARY KEY,
    cursor_value VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP
);
