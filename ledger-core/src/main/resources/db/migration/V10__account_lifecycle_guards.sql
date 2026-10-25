-- Lifecycle rules enforced by the database, so no application bug can break them:
--   1. a CLOSED account is always empty;
--   2. only ACTIVE customer accounts take new entries.

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_closed_is_empty CHECK (status <> 'CLOSED' OR balance = 0);

-- The entry trigger now locks the account row and looks at its status first. If another transaction is
-- freezing or closing the account at this very moment, this one waits for it and then sees the new status.
CREATE OR REPLACE FUNCTION stamp_and_apply_entry() RETURNS trigger AS $$
DECLARE
current_status VARCHAR(16);
BEGIN
SELECT status INTO current_status
FROM accounts
WHERE id = NEW.account_id AND NOT allow_negative
    FOR NO KEY UPDATE;

IF current_status IS NOT NULL AND current_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'Account % is % and cannot take new entries (account_not_active)',
            NEW.account_id, current_status
            USING ERRCODE = '23514';
END IF;

UPDATE accounts
SET balance        = balance + NEW.amount,
    last_entry_seq = last_entry_seq + 1,
    last_posted_at = GREATEST(clock_timestamp(), last_posted_at)
WHERE id = NEW.account_id AND NOT allow_negative
    RETURNING balance, last_entry_seq, last_posted_at
INTO NEW.balance_after, NEW.account_seq, NEW.posted_at;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;