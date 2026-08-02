-- COD phase 2 — the handover of a driver's cash to the depot.
--
-- Between the shop paying and the accountant posting, the money exists only in a driver's pocket.
-- The ERP cannot see that interval: it learns of the payment once it has already arrived. This table
-- is the only place that can answer "how much is out there right now".

CREATE TABLE IF NOT EXISTS cash_remittances (
    id               UUID PRIMARY KEY,
    driver_id        UUID           NOT NULL,
    -- Snapshotted: a driver renamed or deactivated later must still read correctly on old handovers.
    driver_name      VARCHAR(150),

    status           VARCHAR(20)    NOT NULL DEFAULT 'OPEN',

    -- The platform's own account of what the driver took. Neither party can influence it.
    expected_total   NUMERIC(19,3)  NOT NULL DEFAULT 0,
    -- What the driver says he has.
    declared_total   NUMERIC(19,3),
    -- What somebody else counted.
    received_total   NUMERIC(19,3),
    -- received - expected. Derived, never typed: one "amount" field filled by one person is an
    -- honour system with extra steps.
    discrepancy      NUMERIC(19,3),

    declared_by      UUID,
    declared_at      TIMESTAMP,
    received_by      UUID,
    received_by_name VARCHAR(150),
    received_at      TIMESTAMP,
    reconciled_by    UUID,
    reconciled_at    TIMESTAMP,
    note             TEXT,

    opened_at        TIMESTAMP      NOT NULL DEFAULT NOW(),
    closed_at        TIMESTAMP,

    CONSTRAINT ck_cash_remittances_totals CHECK (
        (declared_total IS NULL OR declared_total >= 0) AND
        (received_total IS NULL OR received_total >= 0)
    )
);

-- One open handover per driver at a time. A partial unique index rather than a plain constraint:
-- a driver has many closed handovers behind him and exactly one in flight.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cash_remittances_one_open_per_driver
    ON cash_remittances (driver_id) WHERE status IN ('OPEN', 'DECLARED');

CREATE INDEX IF NOT EXISTS idx_cash_remittances_status ON cash_remittances (status);

ALTER TABLE cash_collections
    ADD CONSTRAINT fk_cash_collections_remittance
    FOREIGN KEY (remittance_id) REFERENCES cash_remittances (id) ON DELETE SET NULL;

COMMENT ON COLUMN cash_remittances.discrepancy IS
    'received_total - expected_total. Negative means money is missing. Always computed, never supplied by a caller.';
