-- REQ-027: Add expires_at column to transactions table for idempotency key TTL
ALTER TABLE transactions ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '24 hours');
CREATE INDEX idx_transactions_expires_at ON transactions (expires_at);
