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

CREATE INDEX ix_audit_log_resource ON audit_log(resource_type, resource_id);
CREATE INDEX ix_audit_log_actor ON audit_log(actor_id, created_at);
