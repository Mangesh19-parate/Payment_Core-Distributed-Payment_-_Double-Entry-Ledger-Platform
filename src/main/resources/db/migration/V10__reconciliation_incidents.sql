CREATE TABLE reconciliation_incidents (
    id                  UUID PRIMARY KEY,
    account_id          UUID NOT NULL REFERENCES accounts(id),
    cached_balance      BIGINT NOT NULL,
    ledger_balance      BIGINT NOT NULL,
    discrepancy         BIGINT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'DETECTED' CHECK (status IN ('DETECTED', 'INVESTIGATING', 'RESOLVED', 'DISMISSED')),
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ,
    resolved_by         UUID REFERENCES users(id),
    resolution_notes    TEXT
);

CREATE INDEX ix_reconciliation_incidents_account ON reconciliation_incidents(account_id);
CREATE INDEX ix_reconciliation_incidents_status ON reconciliation_incidents(status);
