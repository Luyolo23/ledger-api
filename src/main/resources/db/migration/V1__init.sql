CREATE TABLE users (
                       id UUID PRIMARY KEY,
                       email VARCHAR(320) NOT NULL,
                       password_hash VARCHAR(100) NOT NULL,
                       created_at TIMESTAMPTZ NOT NULL,
                       CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE accounts (
                          id UUID PRIMARY KEY,
                          user_id UUID NOT NULL REFERENCES users(id),
                          name VARCHAR(100) NOT NULL,
                          currency VARCHAR(3) NOT NULL,
                          created_at TIMESTAMPTZ NOT NULL,
                          CONSTRAINT ck_accounts_currency CHECK (currency ~ '^[A-Z]{3}$')
    );

CREATE INDEX idx_accounts_user_id ON accounts(user_id);

CREATE TABLE transfers (
                           id UUID PRIMARY KEY,
                           user_id UUID NOT NULL REFERENCES users(id),
                           from_account_id UUID NOT NULL REFERENCES accounts(id),
                           to_account_id UUID NOT NULL REFERENCES accounts(id),
                           amount_minor BIGINT NOT NULL,
                           currency VARCHAR(3) NOT NULL,
                           idempotency_key VARCHAR(255) NOT NULL,
                           created_at TIMESTAMPTZ NOT NULL,
                           CONSTRAINT ck_transfers_amount_positive CHECK (amount_minor > 0),
                           CONSTRAINT ck_transfers_distinct_accounts CHECK (from_account_id <> to_account_id),
                           CONSTRAINT ck_transfers_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_transfers_user_idempotency UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_transfers_user_created ON transfers(user_id, created_at DESC);
CREATE INDEX idx_transfers_from_account ON transfers(from_account_id);
CREATE INDEX idx_transfers_to_account ON transfers(to_account_id);

CREATE TABLE ledger_entries (
                                id UUID PRIMARY KEY,
                                transfer_id UUID NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
                                account_id UUID NOT NULL REFERENCES accounts(id),
                                direction VARCHAR(6) NOT NULL,
                                amount_minor BIGINT NOT NULL,
                                currency VARCHAR(3) NOT NULL,
                                created_at TIMESTAMPTZ NOT NULL,
                                CONSTRAINT ck_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
                                CONSTRAINT ck_ledger_amount_positive CHECK (amount_minor > 0),
                                CONSTRAINT ck_ledger_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT uq_ledger_transfer_direction UNIQUE (transfer_id, direction)
);

CREATE INDEX idx_ledger_account_created ON ledger_entries(account_id, created_at DESC);
CREATE INDEX idx_ledger_transfer_id ON ledger_entries(transfer_id);