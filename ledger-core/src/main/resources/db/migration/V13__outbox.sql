-- The outbox: an event is written in the same transaction as the change that caused it, and a separate
-- relay delivers it to other systems. So an event exists if and only if the change committed.
CREATE TABLE outbox_events (
                               seq             BIGSERIAL    PRIMARY KEY,
                               id              UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
                               aggregate_type  VARCHAR(32)  NOT NULL,
                               aggregate_id    UUID         NOT NULL,
                               event_type      VARCHAR(64)  NOT NULL,
                               payload         JSONB        NOT NULL,
                               created_at      TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
                               published_at    TIMESTAMPTZ,
                               attempts        INT          NOT NULL DEFAULT 0,
                               next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
                               last_error      VARCHAR(500)
);

-- the relay only ever looks at events that have not been published yet
CREATE INDEX idx_outbox_pending ON outbox_events (seq) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id);