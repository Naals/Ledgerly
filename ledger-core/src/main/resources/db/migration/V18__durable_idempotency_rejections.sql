-- A business refusal is a final outcome, even if the balance or account status later changes.
-- Existing successful response bodies keep their original format.
ALTER TABLE idempotency_keys
    ADD COLUMN error_status SMALLINT,
    ADD COLUMN error_code TEXT,
    ADD COLUMN error_message TEXT,
    ADD CONSTRAINT idempotency_outcome_shape CHECK (
        (error_status IS NULL AND error_code IS NULL AND error_message IS NULL)
        OR
        (response_body IS NULL AND error_status IS NOT NULL
            AND error_status IN (400, 404, 409, 422)
            AND error_code IS NOT NULL AND error_message IS NOT NULL)
    );
