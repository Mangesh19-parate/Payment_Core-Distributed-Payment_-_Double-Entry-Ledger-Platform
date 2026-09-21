CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES transactions(id) DEFERRABLE INITIALLY DEFERRED,
    account_id      UUID NOT NULL REFERENCES accounts(id) DEFERRABLE INITIALLY DEFERRED,
    entry_type      TEXT NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount          BIGINT NOT NULL CHECK (amount > 0),
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_ledger_account_time ON ledger_entries(account_id, created_at);
CREATE INDEX ix_ledger_txn ON ledger_entries(transaction_id);
