-- A statement needs a trustworthy order of entries and a running balance. Both are decided while the
-- account row is locked, so they follow the order in which entries were really applied to the account:
--   account_seq   1, 2, 3 ... per account, gap-free (a rolled-back posting rolls its number back too)
--   balance_after the account's balance right after this entry
--   posted_at     when the entry was applied to the account, never earlier than the previous entry's
-- Only accounts that cannot go negative are stamped; the bank's own accounts stay unstamped (NULL).
ALTER TABLE accounts
    ADD COLUMN last_entry_seq BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_posted_at TIMESTAMPTZ;

ALTER TABLE ledger_entries
    ADD COLUMN account_seq   BIGINT,
    ADD COLUMN balance_after BIGINT,
    ADD COLUMN posted_at     TIMESTAMPTZ;

-- Stamp the entries that already exist, in id order. The append-only trigger is lifted for this one
-- backfill only; if anything fails, Flyway rolls the whole migration back, trigger state included.
ALTER TABLE ledger_entries DISABLE TRIGGER trg_entries_append_only;

UPDATE ledger_entries e
SET account_seq = s.seq, balance_after = s.running, posted_at = s.posted
    FROM (SELECT x.id,
               ROW_NUMBER()      OVER w AS seq,
               SUM(x.amount)     OVER w AS running,
               MAX(x.created_at) OVER w AS posted
          FROM ledger_entries x
          JOIN accounts a ON a.id = x.account_id AND NOT a.allow_negative
        WINDOW w AS (PARTITION BY x.account_id ORDER BY x.id)) s
WHERE e.id = s.id;

ALTER TABLE ledger_entries ENABLE TRIGGER trg_entries_append_only;

UPDATE accounts a
SET last_entry_seq = m.max_seq, last_posted_at = m.max_posted
    FROM (SELECT account_id, MAX(account_seq) AS max_seq, MAX(posted_at) AS max_posted
          FROM ledger_entries
         WHERE account_seq IS NOT NULL
         GROUP BY account_id) m
WHERE a.id = m.account_id;

CREATE UNIQUE INDEX uq_entries_account_seq
    ON ledger_entries (account_id, account_seq) WHERE account_seq IS NOT NULL;

CREATE INDEX idx_entries_account_posted
    ON ledger_entries (account_id, posted_at DESC, account_seq DESC) WHERE account_seq IS NOT NULL;

-- The balance trigger now runs BEFORE the insert, so it can stamp the new row.
DROP TRIGGER trg_entries_update_balance ON ledger_entries;
DROP FUNCTION apply_entry_to_balance();

CREATE FUNCTION stamp_and_apply_entry() RETURNS trigger AS $$
BEGIN
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

CREATE TRIGGER trg_entries_stamp
    BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION stamp_and_apply_entry();