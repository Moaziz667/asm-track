-- Closing an operational exception the platform cannot close by itself.
--
-- Most exceptions retire on their own: a partial delivery whose reliquat has been re-imported, a
-- failure that has been re-attempted. What is left is the handful nothing can deduce — the customer
-- cancelled by telephone, refused the remainder for good, or the address turned out not to exist and
-- the order was abandoned. Those sat on the dispatch desk forever, and a count that can only climb
-- is a count nobody reads.
--
-- Two columns rather than a table: the acknowledgement is one fact about one delivery, it is
-- overwritten rather than accumulated, and a row per acknowledgement would buy history nobody asked
-- for. The audit log already records the act.
--
-- `acknowledged_at` is compared against the delivery's own `updated_at`, never treated as final: if
-- the delivery moves after being acknowledged, the exception comes back. Silencing today's problem
-- must not silence tomorrow's on the same shipment.

ALTER TABLE deliveries
    ADD COLUMN IF NOT EXISTS ops_acknowledged_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS ops_acknowledged_by UUID;

COMMENT ON COLUMN deliveries.ops_acknowledged_at IS
    'When a dispatcher declared this exception handled. Void as soon as updated_at moves past it.';
COMMENT ON COLUMN deliveries.ops_acknowledged_by IS
    'Who declared it handled — app user id, so the desk can name them.';
