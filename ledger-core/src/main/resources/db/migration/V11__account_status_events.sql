-- Every change of an account's status is kept, with the reason given for it. The table is append-only.
CREATE TABLE account_status_events (
                                       id          BIGSERIAL    PRIMARY KEY,
                                       account_id  UUID         NOT NULL REFERENCES accounts (id),
                                       from_status VARCHAR(16)  NOT NULL,
                                       to_status   VARCHAR(16)  NOT NULL,
                                       reason      VARCHAR(255),
                                       created_at  TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
                                       CONSTRAINT ck_status_event_changes_something CHECK (from_status <> to_status)
);

CREATE INDEX idx_account_status_events_account ON account_status_events (account_id, id);

CREATE TRIGGER trg_status_events_append_only
    BEFORE UPDATE OR DELETE ON account_status_events
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();