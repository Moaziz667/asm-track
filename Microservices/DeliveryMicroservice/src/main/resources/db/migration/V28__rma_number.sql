-- ADR-033 — Each RMA gets its OWN human-readable reference (RET-00001), distinct from the original
-- order/delivery ref it shares. Atomic Postgres sequence; existing rows backfilled in creation order.
CREATE SEQUENCE IF NOT EXISTS rma_number_seq START 1;

ALTER TABLE rma ADD COLUMN rma_number VARCHAR(20);

-- Backfill existing RMAs deterministically by creation order.
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn FROM rma
)
UPDATE rma r
SET rma_number = 'RET-' || LPAD(o.rn::text, 5, '0')
FROM ordered o
WHERE r.id = o.id;

-- Point the sequence at the next free number (COUNT+1); with is_called=false the next nextval() returns it.
SELECT setval('rma_number_seq', (SELECT COUNT(*) FROM rma) + 1, false);

ALTER TABLE rma ALTER COLUMN rma_number SET NOT NULL;
ALTER TABLE rma ADD CONSTRAINT rma_number_key UNIQUE (rma_number);
