-- The ledger's outbox gets the same guarantee as the payment outbox: an undelivered event is never edited or lost.
-- Only delivery bookkeeping may change; a delivered event may be deleted (retention).
CREATE FUNCTION protect_outbox_events() RETURNS trigger AS $$
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
       OR NEW.payload <> OLD.payload OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Outbox event % cannot be changed (outbox_event_immutable)', OLD.seq
            USING ERRCODE = '55000';
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_protect
    BEFORE UPDATE OR DELETE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION protect_outbox_events();

-- retention deletes by delivery time
CREATE INDEX idx_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;