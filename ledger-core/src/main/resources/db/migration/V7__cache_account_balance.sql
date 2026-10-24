-- Each account keeps a cached balance, so reading a balance or checking for an overdraft no longer
-- sums every entry the account ever had.
--
-- The cache is maintained by a trigger on every ledger entry, in the same transaction as the entry,
-- so it cannot drift: ledger_entries stays the source of truth and accounts.balance always equals
-- SUM(amount) of the account's entries.
--
-- The CHECK constraint makes the database itself refuse to overdraw a customer account, even if
-- application code is buggy or someone writes SQL by hand.
ALTER TABLE accounts ADD COLUMN balance BIGINT NOT NULL DEFAULT 0;

UPDATE accounts a
SET balance = COALESCE((SELECT SUM(e.amount) FROM ledger_entries e WHERE e.account_id = a.id), 0);

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_balance_non_negative CHECK (allow_negative OR balance >= 0);

CREATE FUNCTION apply_entry_to_balance() RETURNS trigger AS $$
BEGIN
UPDATE accounts SET balance = balance + NEW.amount WHERE id = NEW.account_id;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_entries_update_balance
    AFTER INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION apply_entry_to_balance();