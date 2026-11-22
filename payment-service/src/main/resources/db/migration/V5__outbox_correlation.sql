-- Metadata is separate from the versioned event payload. Existing events have no correlation id.
ALTER TABLE outbox_events
    ADD COLUMN correlation_id VARCHAR(64),
    ADD CONSTRAINT ck_outbox_correlation_id CHECK (
        correlation_id IS NULL OR correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'
    );

ALTER TABLE outbox_events ALTER COLUMN correlation_id
    SET DEFAULT NULLIF(current_setting('ledgerly.correlation_id', true), '');

CREATE OR REPLACE FUNCTION protect_outbox_events() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.published_at IS NULL THEN
            RAISE EXCEPTION 'Outbox event % has not been published and cannot be deleted (outbox_unpublished_event)', OLD.seq
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;
    IF NEW.seq <> OLD.seq OR NEW.id <> OLD.id OR NEW.aggregate_type <> OLD.aggregate_type
       OR NEW.aggregate_id <> OLD.aggregate_id OR NEW.event_type <> OLD.event_type
       OR NEW.payload <> OLD.payload OR NEW.created_at <> OLD.created_at
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'Outbox event % cannot be changed (outbox_event_immutable)', OLD.seq
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
