-- COD phase 1 — what the driver reports taking at each delivery.
--
-- Separate from proof_of_delivery on purpose: the POD is written once and never changes, which is
-- what makes it evidence. Cash keeps moving after the handover (declared, counted, disputed,
-- settled), and putting that lifecycle inside the proof would make the proof mutable.

CREATE TABLE IF NOT EXISTS cash_collections (
    id               UUID PRIMARY KEY,
    delivery_id      UUID           NOT NULL,
    order_id         UUID           NOT NULL,

    -- Frozen at creation. The reconciliation compares the driver's report against this figure; if it
    -- could be recomputed from orders.cod_amount after an ERP re-sync, every past discrepancy would
    -- silently become right or wrong.
    amount_expected  NUMERIC(19,3)  NOT NULL,
    amount_collected NUMERIC(19,3)  NOT NULL DEFAULT 0,

    method           VARCHAR(20)    NOT NULL DEFAULT 'NONE',
    cheque_number    VARCHAR(60),
    cheque_bank      VARCHAR(100),
    cheque_date      DATE,

    reason           VARCHAR(60),
    reason_label     VARCHAR(200),

    status           VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    driver_id        UUID           NOT NULL,
    collected_at     TIMESTAMP      NOT NULL DEFAULT NOW(),

    -- Null while the driver still holds the money: that is precisely the set the
    -- "cash in circulation" figure is built from.
    remittance_id    UUID,

    CONSTRAINT uq_cash_collections_delivery UNIQUE (delivery_id),
    CONSTRAINT ck_cash_collections_amounts  CHECK (amount_expected >= 0 AND amount_collected >= 0),
    -- A cheque with no number cannot be traced back to the delivery that accepted it.
    CONSTRAINT ck_cash_collections_cheque   CHECK (method <> 'CHEQUE' OR cheque_number IS NOT NULL),
    -- Taking nothing is allowed; taking nothing without saying why is not.
    CONSTRAINT ck_cash_collections_reason   CHECK (status <> 'REFUSED' OR reason IS NOT NULL)
);

-- The driver's outstanding cash: everything he collected that has not been handed over yet.
CREATE INDEX IF NOT EXISTS idx_cash_collections_driver_open
    ON cash_collections (driver_id) WHERE remittance_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_cash_collections_remittance
    ON cash_collections (remittance_id);

COMMENT ON TABLE cash_collections IS
    'One declaration per delivery of what the driver took. Not an accounting entry — the ERP stays the book of accounts and the money never passes through this platform.';
