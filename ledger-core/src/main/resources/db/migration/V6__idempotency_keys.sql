-- Idempotency keys: remember the outcome of a request so that a retry with the same key
-- does not repeat it. The row is written in the same database transaction as the operation
-- itself, so the key and the operation either both exist or neither does.
CREATE TABLE idempotency_keys (
                                  scope           VARCHAR(32)  NOT NULL,
                                  idempotency_key VARCHAR(64)  NOT NULL,
                                  request_hash    CHAR(64)     NOT NULL,
                                  response_body   TEXT,
                                  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                  PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX idx_idempotency_keys_created_at ON idempotency_keys (created_at);