CREATE TABLE IF NOT EXISTS processed_requests (
    idempotency_key VARCHAR(100) PRIMARY KEY,
    response_status INTEGER NOT NULL,
    response_body TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_processed_requests_created_at ON processed_requests(created_at);
