-- The outbox for payment events. Every change of a payment's status leaves exactly one event here, written by a
-- trigger in the same transaction as the change. So an event exists if and only if the status change committed, and
-- no code path (service, event handler, hand-written SQL) can change a status without one.
--
-- A relay (ADR 0016) delivers the events to Kafka, in order for each payment.

ALTER TABLE payments ADD COLUMN status_changes INT NOT NULL DEFAULT 0;   -- how many times the status changed

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

-- the relay only looks at events that have not been published yet ...
CREATE INDEX idx_outbox_pending ON outbox_events (seq) WHERE published_at IS NULL;
-- ... and may not publish a payment's event while an earlier one of the same payment is still waiting
CREATE INDEX idx_outbox_aggregate_pending ON outbox_events (aggregate_id, seq) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id, seq);

-- An unpublished event is never edited or lost; delivery bookkeeping is all that may change. Published events
-- may be deleted once nobody needs them (retention).
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

-- Counts the status change on the payment itself (not by counting events, which retention deletes), so every event
-- carries a sequence number that consumers can use to recognise an old event arriving late.
CREATE FUNCTION count_payment_status_change() RETURNS trigger AS $$
BEGIN
    NEW.status_changes := OLD.status_changes + 1;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_count_changes
    BEFORE UPDATE OF status ON payments
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION count_payment_status_change();

-- One event per status change. Contract: /contracts/payment-status-changed.v1.*.json
CREATE FUNCTION record_payment_status_change() RETURNS trigger AS $$
BEGIN
INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
VALUES ('Payment', NEW.id, 'PaymentStatusChanged', jsonb_build_object(
        'version', 1,
        'paymentId', NEW.id,
        'sequence', NEW.status_changes,
        'status', NEW.status,
        'previousStatus', OLD.status,
        'captureMode', NEW.capture_mode,
        'fromAccountId', NEW.from_account_id,
        'toAccountId', NEW.to_account_id,
        'amountMinor', NEW.amount_minor,
        'currency', NEW.currency,
        'failureCode', NEW.failure_code,
        'ledgerTransactionId', NEW.ledger_transaction_id,
        'authorizationExpiresAt',
        to_char(NEW.authorization_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'occurredAt',
        to_char(NEW.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')));
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_record_change
    AFTER UPDATE OF status ON payments
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION record_payment_status_change();