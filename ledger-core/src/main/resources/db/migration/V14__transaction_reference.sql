-- A client-supplied reference (for example the id of a payment in another service) travels with a
-- ledger transaction and into its event, so other services can find their own records again
-- without ever knowing ledger ids. It is a label, not a key: it is not unique.
ALTER TABLE ledger_transactions
    ADD COLUMN reference VARCHAR(64),
    ADD CONSTRAINT ck_transactions_reference_not_blank CHECK (reference IS NULL OR btrim(reference) <> '');

CREATE INDEX idx_transactions_reference ON ledger_transactions (reference) WHERE reference IS NOT NULL;