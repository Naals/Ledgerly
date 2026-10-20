-- System accounts (cash-in-bank, fees, ...) are looked up by a well-known reference and currency.
-- A partial unique index guarantees there is exactly one per reference and currency, even when
-- several transactions or application instances try to create it at the same time.
CREATE UNIQUE INDEX uq_accounts_system_ref_currency
    ON accounts (owner_ref, currency)
    WHERE type = 'SYSTEM';