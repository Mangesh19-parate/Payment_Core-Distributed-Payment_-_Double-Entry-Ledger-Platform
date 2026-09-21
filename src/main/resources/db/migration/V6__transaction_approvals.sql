CREATE TABLE transaction_approvals (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL UNIQUE REFERENCES transactions(id),
    requested_by    UUID NOT NULL REFERENCES users(id),
    approved_by     UUID REFERENCES users(id),
    status          TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at     TIMESTAMPTZ,
    CHECK (approved_by IS NULL OR approved_by <> requested_by)
);
