-- Every change of a hold's status leaves exactly one HoldStatusChanged event in the outbox, written by the database in
-- the same transaction as the change. Other services (payment-service) learn that a hold was captured, released or
-- expired from this event instead of asking. Contract: /contracts/hold-status-changed.v1.*.json
--
-- The trigger is DEFERRED: it runs when the transaction commits and reads the hold as it is THEN. A capture marks the
-- hold CAPTURED first (which frees its funds) and records the ledger transaction that moved the money afterwards, in the
-- same transaction; an ordinary trigger would announce the capture before that transaction is known.
CREATE FUNCTION record_hold_status_change() RETURNS trigger AS $$
DECLARE
h holds%ROWTYPE;
BEGIN
SELECT * INTO h FROM holds WHERE id = NEW.id;

INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
VALUES ('Hold', h.id, 'HoldStatusChanged', jsonb_build_object(
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

CREATE CONSTRAINT TRIGGER trg_holds_record_change
    AFTER UPDATE OF status ON holds
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION record_hold_status_change();