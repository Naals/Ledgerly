-- A payment can be paid in one step (AUTOMATIC: the ledger transfers at once) or in two (MANUAL: the ledger first
-- HOLDS the money, and a later capture moves it). The lifecycle, enforced here:
--
--   AUTOMATIC   PENDING -> COMPLETED | FAILED                      COMPLETED -> REVERSED
--   MANUAL      PENDING -> AUTHORIZED | FAILED
--               AUTHORIZED -> COMPLETED | CANCELLED | EXPIRED      COMPLETED -> REVERSED
--
-- A manual payment remembers its ledger hold and when the authorization runs out. Nothing moves before capture,
-- so only COMPLETED (and later REVERSED) payments have a ledger transaction, exactly as before.

ALTER TABLE payments
    ADD COLUMN capture_mode             VARCHAR(16) NOT NULL DEFAULT 'AUTOMATIC',
    ADD COLUMN ledger_hold_id           UUID,
    ADD COLUMN authorization_expires_at TIMESTAMPTZ;

ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments
    ADD CONSTRAINT payments_status_check
        CHECK (status IN ('PENDING', 'AUTHORIZED', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED', 'REVERSED')),
    ADD CONSTRAINT ck_payment_capture_mode CHECK (capture_mode IN ('AUTOMATIC', 'MANUAL')),
    -- only a manual payment has a hold, and one that got past PENDING (or failed) must have one
    ADD CONSTRAINT ck_payment_hold_only_if_manual CHECK (ledger_hold_id IS NULL OR capture_mode = 'MANUAL'),
    ADD CONSTRAINT ck_payment_manual_has_hold
        CHECK (capture_mode = 'AUTOMATIC' OR status IN ('PENDING', 'FAILED') OR ledger_hold_id IS NOT NULL),
    ADD CONSTRAINT ck_payment_failed_has_no_hold CHECK (status <> 'FAILED' OR ledger_hold_id IS NULL),
    -- the hold and the moment its authorization ends are learned together
    ADD CONSTRAINT ck_payment_hold_has_expiry CHECK ((ledger_hold_id IS NULL) = (authorization_expires_at IS NULL)),
    ADD CONSTRAINT ck_payment_authorization_statuses_are_manual
        CHECK (capture_mode = 'MANUAL' OR status NOT IN ('AUTHORIZED', 'CANCELLED', 'EXPIRED'));

-- a ledger hold belongs to at most one payment
CREATE UNIQUE INDEX uq_payments_ledger_hold ON payments (ledger_hold_id) WHERE ledger_hold_id IS NOT NULL;

-- the reconciliation job only looks at authorizations that may have run out
CREATE INDEX idx_payments_authorization_due ON payments (authorization_expires_at) WHERE status = 'AUTHORIZED';

CREATE OR REPLACE FUNCTION enforce_payment_rules() RETURNS trigger AS $$
BEGIN
    IF NEW.id <> OLD.id
       OR NEW.idempotency_key <> OLD.idempotency_key
       OR NEW.request_hash <> OLD.request_hash
       OR NEW.from_account_id <> OLD.from_account_id
       OR NEW.to_account_id <> OLD.to_account_id
       OR NEW.amount_minor <> OLD.amount_minor
       OR NEW.currency <> OLD.currency
       OR NEW.capture_mode <> OLD.capture_mode THEN
        RAISE EXCEPTION 'The request of payment % cannot be changed (payment_immutable)', OLD.id
            USING ERRCODE = '23514';
END IF;

    -- once the ledger hold is known it is the payment's for good
    IF OLD.ledger_hold_id IS NOT NULL
       AND (NEW.ledger_hold_id IS DISTINCT FROM OLD.ledger_hold_id
            OR NEW.authorization_expires_at IS DISTINCT FROM OLD.authorization_expires_at) THEN
        RAISE EXCEPTION 'The hold of payment % cannot be changed (payment_hold_immutable)', OLD.id
            USING ERRCODE = '23514';
END IF;

    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'PENDING'    AND NEW.status = 'COMPLETED'  AND NEW.capture_mode = 'AUTOMATIC')
        OR (OLD.status = 'PENDING'    AND NEW.status = 'AUTHORIZED' AND NEW.capture_mode = 'MANUAL')
        OR (OLD.status = 'PENDING'    AND NEW.status = 'FAILED')
        OR (OLD.status = 'AUTHORIZED' AND NEW.status IN ('COMPLETED', 'CANCELLED', 'EXPIRED'))
        OR (OLD.status = 'COMPLETED'  AND NEW.status = 'REVERSED')) THEN
        RAISE EXCEPTION 'Payment % cannot go from % to % (invalid_payment_transition)',
            OLD.id, OLD.status, NEW.status
            USING ERRCODE = '23514';
END IF;

    NEW.updated_at := clock_timestamp();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;