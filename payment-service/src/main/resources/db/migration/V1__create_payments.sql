-- A payment: a customer's request to move money between two accounts, executed through the ledger.
-- The rules of its lifecycle live in the database:
--   PENDING   -> COMPLETED | FAILED
--   COMPLETED -> REVERSED
-- and nothing else. The request itself can never be changed after the payment is created.
CREATE TABLE payments (
                          id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                          idempotency_key       VARCHAR(64)  NOT NULL UNIQUE,
                          request_hash          CHAR(64)     NOT NULL,
                          from_account_id       UUID         NOT NULL,
                          to_account_id         UUID         NOT NULL,
                          amount_minor          BIGINT       NOT NULL CHECK (amount_minor > 0),
                          currency              CHAR(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status                VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                          CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED')),
    failure_code          VARCHAR(64),
    ledger_transaction_id UUID,
    attempts              INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_payment_accounts_differ CHECK (from_account_id <> to_account_id),
    CONSTRAINT ck_payment_failed_has_code CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT ck_payment_done_has_transaction
        CHECK ((status IN ('COMPLETED', 'REVERSED')) = (ledger_transaction_id IS NOT NULL))
);

-- a ledger transaction belongs to at most one payment
CREATE UNIQUE INDEX uq_payments_ledger_transaction
    ON payments (ledger_transaction_id) WHERE ledger_transaction_id IS NOT NULL;

-- the retry job only looks at payments that are still waiting for the ledger
CREATE INDEX idx_payments_pending ON payments (updated_at) WHERE status = 'PENDING';

CREATE FUNCTION enforce_payment_rules() RETURNS trigger AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.request_hash <> OLD.request_hash
       OR NEW.from_account_id <> OLD.from_account_id
       OR NEW.to_account_id <> OLD.to_account_id
       OR NEW.amount_minor <> OLD.amount_minor
       OR NEW.currency <> OLD.currency THEN
        RAISE EXCEPTION 'The request of payment % cannot be changed (payment_immutable)', OLD.id
            USING ERRCODE = '23514';
END IF;

    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'PENDING' AND NEW.status IN ('COMPLETED', 'FAILED'))
        OR (OLD.status = 'COMPLETED' AND NEW.status = 'REVERSED')) THEN
        RAISE EXCEPTION 'Payment % cannot go from % to % (invalid_payment_transition)',
            OLD.id, OLD.status, NEW.status
            USING ERRCODE = '23514';
END IF;

    NEW.updated_at := clock_timestamp();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_rules
    BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION enforce_payment_rules();

CREATE FUNCTION forbid_payment_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Payments are never deleted (payment_not_deletable)' USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payments_no_delete
    BEFORE DELETE ON payments
    FOR EACH ROW EXECUTE FUNCTION forbid_payment_delete();