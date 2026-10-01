CREATE TABLE consumer_event_versions (
    consumer_name           VARCHAR(100) NOT NULL,
    account_id              UUID NOT NULL,
    last_applied_version    BIGINT NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, account_id)
);
