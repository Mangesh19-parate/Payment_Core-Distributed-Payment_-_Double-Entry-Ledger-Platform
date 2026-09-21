CREATE TABLE transactions (
    id                      UUID PRIMARY KEY,
    principal_id            UUID NOT NULL REFERENCES users(id),
    idempotency_key         TEXT NOT NULL,
    request_hash            TEXT NOT NULL,   -- SHA-256 of canonical field concatenation, see TRD REQ-024
    source_account_id       UUID NOT NULL REFERENCES accounts(id) DEFERRABLE INITIALLY DEFERRED,
    destination_account_id  UUID NOT NULL REFERENCES accounts(id) DEFERRABLE INITIALLY DEFERRED,
    amount                  BIGINT NOT NULL CHECK (amount > 0 AND amount <= 1000000000), -- ceiling = ₹1 crore in paise
    currency                CHAR(3) NOT NULL,
    status                  TEXT NOT NULL CHECK (status IN ('CREATED', 'AWAITING_APPROVAL', 'POSTED', 'FAILED', 'REJECTED', 'REVERSED')),
    failure_reason          TEXT,
    type                    TEXT NOT NULL CHECK (type IN ('TRANSFER', 'REFUND', 'REVERSAL')),
    reference_txn_id        UUID REFERENCES transactions(id) DEFERRABLE INITIALLY DEFERRED,  -- set for REVERSAL rows
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at               TIMESTAMPTZ,
    CHECK (source_account_id <> destination_account_id)
);

CREATE UNIQUE INDEX ux_txn_idem_key ON transactions(principal_id, idempotency_key);
CREATE UNIQUE INDEX ux_one_reversal_per_transaction
    ON transactions(reference_txn_id) WHERE type = 'REVERSAL';
