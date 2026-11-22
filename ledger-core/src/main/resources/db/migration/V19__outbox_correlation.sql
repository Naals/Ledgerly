-- Correlation is delivery metadata. Existing events keep their payload and may have no correlation id.
ALTER TABLE outbox_events
    ADD COLUMN correlation_id VARCHAR(64)
        DEFAULT NULLIF(current_setting('ledgerly.correlation_id', true), ''),
    ADD CONSTRAINT ck_outbox_correlation_id
        CHECK (correlation_id IS NULL OR correlation_id ~ '^[A-Za-z0-9._-]{1,64}$');

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

-- A hold event is deferred until COMMIT so it can include the capture's transaction id.
-- Snapshot correlation when status changes, since another operation in the same transaction may
-- subsequently bind a different request id. NEW carries this snapshot into the deferred trigger.
ALTER TABLE holds ADD COLUMN status_correlation_id VARCHAR(64),
    ADD CONSTRAINT ck_hold_status_correlation_id
        CHECK (status_correlation_id IS NULL OR status_correlation_id ~ '^[A-Za-z0-9._-]{1,64}$');

CREATE FUNCTION stamp_hold_status_correlation() RETURNS trigger AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        NEW.status_correlation_id := NULLIF(current_setting('ledgerly.correlation_id', true), '');
    ELSE
        NEW.status_correlation_id := OLD.status_correlation_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_holds_status_correlation
    BEFORE UPDATE ON holds
    FOR EACH ROW EXECUTE FUNCTION stamp_hold_status_correlation();

CREATE OR REPLACE FUNCTION record_hold_status_change() RETURNS trigger AS $$
DECLARE
h holds%ROWTYPE;
BEGIN
SELECT * INTO h FROM holds WHERE id = NEW.id;

INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, correlation_id, payload)
VALUES ('Hold', h.id, 'HoldStatusChanged', NEW.status_correlation_id, jsonb_build_object(
        'version', 1,
        'holdId', h.id,
        'status', h.status,
        'previousStatus', OLD.status,
        'accountId', h.account_id,
        'toAccountId', h.to_account_id,
        'amountMinor', h.amount_minor,
        'currency', h.currency,
        'reference', h.reference,
        'expiresAt', to_char(h.expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'capturedMinor', h.captured_minor,
        'ledgerTransactionId', h.ledger_transaction_id,
        'occurredAt', to_char(h.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')));
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

