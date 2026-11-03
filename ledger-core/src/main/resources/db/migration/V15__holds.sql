-- Holds reserve part of a customer's balance for a payment that is authorized but not yet captured.
--
--   accounts.held   the sum of the account's ACTIVE holds, kept by a trigger in the same transaction
--   available       balance - held
--
-- The database refuses to let available go below zero (balance >= held). So neither a new hold nor a
-- debit can ever touch funds that are reserved, whatever code or hand-written SQL tries to.

ALTER TABLE accounts ADD COLUMN held BIGINT NOT NULL DEFAULT 0;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_held_non_negative CHECK (held >= 0);

-- balance >= held >= 0 also implies balance >= 0, so this replaces the overdraft check from V7
ALTER TABLE accounts DROP CONSTRAINT ck_accounts_balance_non_negative;
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_available_non_negative CHECK (allow_negative OR balance >= held);

CREATE TABLE holds (
                       id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                       account_id            UUID         NOT NULL,
                       to_account_id         UUID         NOT NULL,
                       amount_minor          BIGINT       NOT NULL CHECK (amount_minor > 0),
                       currency              CHAR(3)      NOT NULL,
                       status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
                       reference             VARCHAR(64),
                       expires_at            TIMESTAMPTZ  NOT NULL,
                       captured_minor        BIGINT,
                       ledger_transaction_id UUID         REFERENCES ledger_transactions (id),
                       created_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
                       updated_at            TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    -- both accounts must exist and be held in the hold's currency
                       CONSTRAINT fk_holds_account_currency    FOREIGN KEY (account_id, currency)    REFERENCES accounts (id, currency),
                       CONSTRAINT fk_holds_to_account_currency FOREIGN KEY (to_account_id, currency) REFERENCES accounts (id, currency),
                       CONSTRAINT ck_hold_accounts_differ      CHECK (account_id <> to_account_id),
                       CONSTRAINT ck_hold_captured_amount      CHECK ((status = 'CAPTURED') = (captured_minor IS NOT NULL)),
                       CONSTRAINT ck_hold_capture_within_hold  CHECK (captured_minor IS NULL OR captured_minor BETWEEN 1 AND amount_minor),
                       CONSTRAINT ck_hold_transaction_only_if_captured CHECK (ledger_transaction_id IS NULL OR status = 'CAPTURED'),
                       CONSTRAINT ck_hold_reference_not_blank  CHECK (reference IS NULL OR btrim(reference) <> '')
);

CREATE INDEX idx_holds_account_active ON holds (account_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_holds_due            ON holds (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_holds_reference      ON holds (reference) WHERE reference IS NOT NULL;

-- The rules of a hold's life. Its terms never change; it leaves ACTIVE exactly once; it can only be
-- captured before it expires and only be marked EXPIRED after it did.
CREATE FUNCTION enforce_hold_rules() RETURNS trigger AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.account_id <> OLD.account_id OR NEW.to_account_id <> OLD.to_account_id
       OR NEW.amount_minor <> OLD.amount_minor OR NEW.currency <> OLD.currency
       OR NEW.expires_at <> OLD.expires_at OR NEW.reference IS DISTINCT FROM OLD.reference
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'The terms of hold % cannot be changed (hold_terms_immutable)', OLD.id
            USING ERRCODE = '23514';
END IF;

    IF OLD.status <> 'ACTIVE' THEN
        -- a finished hold stays as it ended; only its ledger transaction id may be recorded, once
        IF NEW.status <> OLD.status OR NEW.captured_minor IS DISTINCT FROM OLD.captured_minor
           OR (OLD.ledger_transaction_id IS NOT NULL
               AND NEW.ledger_transaction_id IS DISTINCT FROM OLD.ledger_transaction_id) THEN
            RAISE EXCEPTION 'Hold % is already % and cannot change (hold_not_active)', OLD.id, OLD.status
                USING ERRCODE = '23514';
END IF;
    ELSIF NEW.status = 'CAPTURED' AND NEW.expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'Hold % has expired and cannot be captured (hold_expired)', OLD.id
            USING ERRCODE = '23514';
    ELSIF NEW.status = 'EXPIRED' AND NEW.expires_at > clock_timestamp() THEN
        RAISE EXCEPTION 'Hold % has not expired yet (hold_not_due)', OLD.id
            USING ERRCODE = '23514';
END IF;

    NEW.updated_at := clock_timestamp();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_holds_rules
    BEFORE UPDATE ON holds
    FOR EACH ROW EXECUTE FUNCTION enforce_hold_rules();

-- Keeps accounts.held equal to the sum of the account's ACTIVE holds. The UPDATE of accounts also
-- re-checks ck_accounts_available_non_negative: a hold that would reserve more than is available fails here.
CREATE FUNCTION apply_hold_to_account() RETURNS trigger AS $$
DECLARE
holder RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'A hold starts ACTIVE (hold_must_start_active)' USING ERRCODE = '23514';
END IF;

SELECT status, allow_negative INTO holder
FROM accounts WHERE id = NEW.account_id FOR NO KEY UPDATE;
IF holder.allow_negative THEN
            RAISE EXCEPTION 'Only customer accounts can hold funds (hold_on_bank_account)' USING ERRCODE = '23514';
END IF;
        IF holder.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'Account % is % and cannot take new holds (account_not_active)',
                NEW.account_id, holder.status USING ERRCODE = '23514';
END IF;

UPDATE accounts SET held = held + NEW.amount_minor WHERE id = NEW.account_id;
ELSIF OLD.status = 'ACTIVE' AND NEW.status <> 'ACTIVE' THEN
UPDATE accounts SET held = held - OLD.amount_minor WHERE id = OLD.account_id;
END IF;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_holds_apply
    AFTER INSERT OR UPDATE ON holds
                        FOR EACH ROW EXECUTE FUNCTION apply_hold_to_account();

CREATE TRIGGER trg_holds_no_delete
    BEFORE DELETE ON holds
    FOR EACH ROW EXECUTE FUNCTION forbid_ledger_mutation();