-- A ledger transaction is the business event; its entries are the individual money movements.
CREATE TABLE ledger_transactions (
                                     id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                                     type        VARCHAR(24)  NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
                                     description VARCHAR(255),
                                     created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
                                id             BIGSERIAL    PRIMARY KEY,
                                transaction_id UUID         NOT NULL REFERENCES ledger_transactions (id),
                                account_id     UUID         NOT NULL,
                                amount         BIGINT       NOT NULL CHECK (amount <> 0),
                                currency       CHAR(3)      NOT NULL,
                                created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                CONSTRAINT fk_entries_account_currency
                                    FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX idx_entries_transaction ON ledger_entries (transaction_id);
CREATE INDEX idx_entries_account     ON ledger_entries (account_id, id);