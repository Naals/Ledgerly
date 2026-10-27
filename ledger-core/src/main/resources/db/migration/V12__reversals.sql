-- The ledger is append-only, so a mistake is corrected by a NEW transaction that exactly undoes the old one.
--   * a REVERSAL names the transaction it reverses, and only a REVERSAL may
--   * a transaction can be reversed at most once
--   * a reversal cannot itself be reversed
--   * a reversal's entries are exactly the opposite of the original's (checked at COMMIT)
ALTER TABLE ledger_transactions DROP CONSTRAINT ledger_transactions_type_check;
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ledger_transactions_type_check
        CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL'));

ALTER TABLE ledger_transactions ADD COLUMN reverses_transaction_id UUID;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT fk_transactions_reverses FOREIGN KEY (reverses_transaction_id) REFERENCES ledger_transactions (id),
    ADD CONSTRAINT uq_transactions_reverses UNIQUE (reverses_transaction_id),
    ADD CONSTRAINT ck_reversal_names_its_original CHECK ((type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL)),
    ADD CONSTRAINT ck_reversal_is_not_self CHECK (reverses_transaction_id <> id);

CREATE FUNCTION check_reversal_is_exact() RETURNS trigger AS $$
DECLARE
original_type VARCHAR(24);
BEGIN
    IF NEW.reverses_transaction_id IS NULL THEN
        RETURN NULL;
END IF;

SELECT type INTO original_type FROM ledger_transactions WHERE id = NEW.reverses_transaction_id;
IF original_type = 'REVERSAL' THEN
        RAISE EXCEPTION 'Transaction % is itself a reversal and cannot be reversed (reversal_of_reversal)',
            NEW.reverses_transaction_id
            USING ERRCODE = '23514';
END IF;

    -- every entry of the reversal must be the exact opposite of an entry of the original, and vice versa
    IF EXISTS (
        (SELECT account_id, currency, amount FROM ledger_entries WHERE transaction_id = NEW.id
         EXCEPT
         SELECT account_id, currency, -amount FROM ledger_entries WHERE transaction_id = NEW.reverses_transaction_id)
        UNION ALL
        (SELECT account_id, currency, -amount FROM ledger_entries WHERE transaction_id = NEW.reverses_transaction_id
         EXCEPT
         SELECT account_id, currency, amount FROM ledger_entries WHERE transaction_id = NEW.id)
    ) THEN
        RAISE EXCEPTION 'Reversal % is not the exact opposite of transaction % (reversal_not_exact)',
            NEW.id, NEW.reverses_transaction_id
            USING ERRCODE = '23514';
END IF;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_transactions_reversal_exact
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_reversal_is_exact();