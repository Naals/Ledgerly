-- V7 updated the cached balance of EVERY account, including the bank's own cash account.
-- Every deposit and withdrawal posts against that one account, so each of them held a lock on the
-- same row until its transaction ended: all cash operations in a currency ran one after another.
--
-- Only accounts that cannot go negative need a cached balance (for the overdraft check), so the
-- bank's own accounts are no longer cached. Their balance is summed from the entries when requested.
CREATE OR REPLACE FUNCTION apply_entry_to_balance() RETURNS trigger AS $$
BEGIN
UPDATE accounts SET balance = balance + NEW.amount
WHERE id = NEW.account_id AND NOT allow_negative;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

UPDATE accounts SET balance = 0 WHERE allow_negative;

COMMENT ON COLUMN accounts.balance IS
    'Cached SUM(amount) of the account''s entries. Maintained only for accounts that cannot go negative; 0 for the bank''s own accounts.';