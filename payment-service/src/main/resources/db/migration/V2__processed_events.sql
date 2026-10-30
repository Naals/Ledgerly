-- Events from other services can be delivered more than once (at-least-once). This table remembers which
-- ones a consumer has already handled, so handling an event twice changes nothing.
CREATE TABLE processed_events (
                                  consumer     VARCHAR(64)  NOT NULL,
                                  event_id     UUID         NOT NULL,
                                  processed_at TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
                                  PRIMARY KEY (consumer, event_id)
);