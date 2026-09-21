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

-- Seed System User & SYSTEM_CASH Account
INSERT INTO users (id, email, password_hash, status)
VALUES ('00000000-0000-0000-0000-000000000001', 'system@platform.internal', '$2a$10$systemhashplaceholder', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO accounts (id, owner_id, currency, cached_balance, version, status, is_system_account)
VALUES ('00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 'INR', 0, 0, 'ACTIVE', true)
ON CONFLICT (id) DO NOTHING;
