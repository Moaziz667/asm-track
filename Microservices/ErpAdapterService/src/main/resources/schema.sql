CREATE TABLE IF NOT EXISTS idempotent_transaction (
    transaction_id VARCHAR(255) PRIMARY KEY,
    erp_order_id VARCHAR(255),
    status VARCHAR(20),
    response_payload TEXT,
    processed_at TIMESTAMP
);
