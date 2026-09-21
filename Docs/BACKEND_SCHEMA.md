# Backend Schema

This is the authoritative DDL. If code and this document disagree, this document is wrong and needs a PR — don't silently let the code drift ahead of it. Full rationale for every design decision here lives in `payment-ledger-spec.md §1.1, §1.5, §1.6, §2`; this file is the DDL and invariant list without the narrative.

## Tables

```sql
CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id                  UUID PRIMARY KEY,
    owner_id            UUID NOT NULL REFERENCES users(id),
    currency            CHAR(3) NOT NULL,
    cached_balance      BIGINT NOT NULL DEFAULT 0,       -- minor units (paise). NEVER float.
    version             BIGINT NOT NULL DEFAULT 0,       -- optimistic lock; also carried on outbound events
    status              TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    is_system_account   BOOLEAN NOT NULL DEFAULT false,  -- SYSTEM_CASH etc. Exempt from sufficiency check.
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id                      UUID PRIMARY KEY,
    principal_id            UUID NOT NULL REFERENCES users(id),
    idempotency_key         TEXT NOT NULL,
    request_hash            TEXT NOT NULL,   -- SHA-256 of canonical field concatenation, see TRD REQ-024
    source_account_id       UUID NOT NULL REFERENCES accounts(id),
    destination_account_id  UUID NOT NULL REFERENCES accounts(id),
    amount                  BIGINT NOT NULL CHECK (amount > 0 AND amount <= 1000000000), -- ceiling = ₹1 crore in paise
    currency                CHAR(3) NOT NULL,
    status                  TEXT NOT NULL CHECK (status IN ('CREATED', 'AWAITING_APPROVAL', 'POSTED', 'FAILED', 'REJECTED', 'REVERSED')),
    failure_reason          TEXT,
    type                    TEXT NOT NULL CHECK (type IN ('TRANSFER', 'REFUND', 'REVERSAL')),
    reference_txn_id        UUID REFERENCES transactions(id),  -- set for REVERSAL rows
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at               TIMESTAMPTZ,
    CHECK (source_account_id <> destination_account_id)
);

CREATE UNIQUE INDEX ux_txn_idem_key ON transactions(principal_id, idempotency_key);
CREATE UNIQUE INDEX ux_one_reversal_per_transaction
    ON transactions(reference_txn_id) WHERE type = 'REVERSAL';

CREATE TABLE transaction_approvals (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL UNIQUE REFERENCES transactions(id),  -- one approval level (v1)
    requested_by    UUID NOT NULL REFERENCES users(id),
    approved_by     UUID REFERENCES users(id),
    status          TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at     TIMESTAMPTZ,
    CHECK (approved_by IS NULL OR approved_by <> requested_by)
);

CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    entry_type      TEXT NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount          BIGINT NOT NULL CHECK (amount > 0),
    currency        CHAR(3) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_ledger_account_time ON ledger_entries(account_id, created_at);
CREATE INDEX ix_ledger_txn ON ledger_entries(transaction_id);

-- Append-only is a permission, not a promise:
--   REVOKE UPDATE, DELETE ON ledger_entries FROM app_role;
--   GRANT SELECT, INSERT ON ledger_entries TO app_role;

CREATE TABLE outbox_events (
    id              BIGSERIAL PRIMARY KEY,
    aggregate_id    UUID NOT NULL,        -- transaction_id or account_id depending on event_type
    event_type      TEXT NOT NULL,        -- TransactionPosted | AccountBalanceChanged | TransactionReversed
    payload         JSONB NOT NULL,       -- includes eventId, eventVersion, occurredAt envelope — see TRD REQ-045
    status          TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    attempt_count   INT NOT NULL DEFAULT 0,
    last_error      TEXT,
    lease_until     TIMESTAMPTZ,          -- claim expiry AND retry backoff — see spec §2
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE TABLE processed_events (
    consumer_name   TEXT NOT NULL,
    event_id        UUID NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    actor_id        UUID REFERENCES users(id),
    action          TEXT NOT NULL,
    resource_type   TEXT NOT NULL,
    resource_id     UUID,
    request_id      TEXT,
    result          TEXT NOT NULL CHECK (result IN ('SUCCESS', 'DENIED', 'FAILED')),
    reason          TEXT,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## Invariant triggers

```sql
CREATE OR REPLACE FUNCTION check_ledger_balance() RETURNS TRIGGER AS $$
DECLARE imbalance BIGINT;
BEGIN
    SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)
    INTO imbalance FROM ledger_entries WHERE transaction_id = NEW.transaction_id;
    IF imbalance <> 0 THEN
        RAISE EXCEPTION 'Ledger imbalance for transaction %: %', NEW.transaction_id, imbalance;
    END IF;
    RETURN NULL;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_ledger_balance
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_balance();

CREATE OR REPLACE FUNCTION check_posting_invariant() RETURNS TRIGGER AS $$
DECLARE entry_count INT;
BEGIN
    IF NEW.status = 'POSTED' AND (OLD.status IS DISTINCT FROM 'POSTED') THEN
        SELECT COUNT(*) INTO entry_count FROM ledger_entries WHERE transaction_id = NEW.id;
        IF entry_count < 2 THEN
            RAISE EXCEPTION 'Transaction % marked POSTED with % ledger entries', NEW.id, entry_count;
        END IF;
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_posting_invariant
    AFTER UPDATE OF status ON transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_posting_invariant();
```

**What these triggers do and don't cover** (state precisely, don't overclaim): they guarantee *structural* posting correctness — entries exist, and they balance to zero. They do not verify *semantic* agreement (currency match, `transactions.amount` equalling the ledger sum) — that remains an application-level check, executed immediately before `COMMIT`. See `TRD REQ-005`/`REQ-006`.

## Formal invariant list (source of truth for what "correct" means)

1. Every `POSTED` transaction has ≥2 `ledger_entries` (exactly 2 for `type = TRANSFER` in v1).
2. `SUM(DEBIT) = SUM(CREDIT)` per `transaction_id`.
3. All entries for one transaction share one currency, matching `transactions.currency`.
4. `transactions.amount` = debit-side sum = credit-side sum, for `POSTED` transactions.
5. Every `amount` is `> 0` and `<=` the configured ceiling.
6. `source_account_id <> destination_account_id`.
7. Both referenced accounts exist and are `ACTIVE` — checked pre-lock (fast reject) **and** re-checked post-lock (authoritative).

## State machines

| Entity | States | Legal transitions | Terminal (illegal out of) |
|---|---|---|---|
| `transactions.status` | `CREATED`, `AWAITING_APPROVAL`, `POSTED`, `FAILED`, `REJECTED`, `REVERSED` | `CREATED→POSTED`, `CREATED→FAILED`, `CREATED→AWAITING_APPROVAL`, `AWAITING_APPROVAL→POSTED`, `AWAITING_APPROVAL→FAILED`, `AWAITING_APPROVAL→REJECTED`, `POSTED→REVERSED` | `REVERSED`, `REJECTED` |
| `outbox_events.status` | `PENDING`, `PUBLISHING`, `PUBLISHED`, `FAILED` | `PENDING→PUBLISHING`, `PUBLISHING→PUBLISHED`, `PUBLISHING→PENDING` (retry/lease-expiry), `PENDING→FAILED` (attempts exhausted) | `PUBLISHED` |
| `accounts.status` | `ACTIVE`, `SUSPENDED`, `CLOSED` | `ACTIVE⇄SUSPENDED`, `ACTIVE→CLOSED`, `SUSPENDED→CLOSED` | `CLOSED` |
| `transaction_approvals.status` | `PENDING`, `APPROVED`, `REJECTED` | `PENDING→APPROVED`, `PENDING→REJECTED` | `APPROVED`, `REJECTED` |

**Note:** a reversal transaction is itself created straight into `POSTED` — `REVERSED` is a status the *original* transaction moves into, never the reversal itself.

## Migration ownership

Flyway-managed. One migration per logical change (a table, an index, a trigger) — not one giant `V1__init.sql`. Any migration touching `ledger_entries`, `outbox_events`, or an invariant trigger requires the human-review gate in `ARCHITECTURE.md`'s "when does the agent stop and ask" section.
