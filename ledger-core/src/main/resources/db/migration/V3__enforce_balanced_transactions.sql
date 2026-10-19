-- Invariant: a ledger transaction must balance to zero per currency.
-- The trigger is deferred, so all entries of a transaction can be inserted first
-- and the check runs once, at COMMIT.
CREATE FUNCTION check_transaction_balanced() RETURNS trigger AS $$
DECLARE
imbalance RECORD;
BEGIN
SELECT e.currency, SUM(e.amount) AS total
INTO imbalance
FROM ledger_entries e
WHERE e.transaction_id = NEW.transaction_id
GROUP BY e.currency
HAVING SUM(e.amount) <> 0
    LIMIT 1;

IF FOUND THEN
        RAISE EXCEPTION 'Ledger transaction % is unbalanced for currency % (sum = %)',
            NEW.transaction_id, imbalance.currency, imbalance.total
            USING ERRCODE = '23514';
END IF;
RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();