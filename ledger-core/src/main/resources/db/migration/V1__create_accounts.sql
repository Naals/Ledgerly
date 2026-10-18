-- Accounts.
-- Money conventions used across the ledger (see docs/adr/0001-ledger-model.md):
--   * Amounts are signed BIGINT minor units (tiyn for KZT). Never floating point.
--   * A positive entry credits an account, a negative entry debits it. Balance = SUM(amount).

CREATE TABLE accounts (
                          id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                          owner_ref      VARCHAR(64)  NOT NULL,
                          type           VARCHAR(16)  NOT NULL CHECK (type IN ('CUSTOMER', 'SYSTEM')),
                          currency       CHAR(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    allow_negative BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- target for the composite FK in V2: an entry's currency must match its account's currency
    CONSTRAINT uq_accounts_id_currency UNIQUE (id, currency),
    -- only system accounts (cash-in-bank, fees, ...) may go negative
    CONSTRAINT ck_accounts_negative_only_system CHECK (allow_negative = FALSE OR type = 'SYSTEM')
);

CREATE INDEX idx_accounts_owner ON accounts (owner_ref);