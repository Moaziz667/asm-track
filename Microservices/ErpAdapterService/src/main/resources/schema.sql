CREATE TABLE IF NOT EXISTS idempotent_transaction (
    transaction_id VARCHAR(255) PRIMARY KEY,
    erp_order_id VARCHAR(255),
    status VARCHAR(20),
    response_payload TEXT,
    processed_at TIMESTAMP
);

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
