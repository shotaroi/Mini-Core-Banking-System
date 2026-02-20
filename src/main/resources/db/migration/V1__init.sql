-- Customer table
CREATE TABLE customer (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_customer_email ON customer(email);

-- Account table with optimistic locking
CREATE TABLE account (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    iban VARCHAR(34) NOT NULL UNIQUE,
    currency VARCHAR(3) NOT NULL,
    balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00 CHECK (balance >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_account_customer ON account(customer_id);
CREATE INDEX idx_account_iban ON account(iban);

-- Ledger entry table
CREATE TABLE ledger_entry (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAW', 'TRANSFER_IN', 'TRANSFER_OUT')),
    amount DECIMAL(19, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    counterparty_account_id BIGINT REFERENCES account(id) ON DELETE SET NULL,
    reference TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ledger_account ON ledger_entry(account_id);
CREATE INDEX idx_ledger_created_at ON ledger_entry(account_id, created_at DESC);

-- Transfer table with idempotency
CREATE TABLE transfer (
    id BIGSERIAL PRIMARY KEY,
    from_account_id BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    to_account_id BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    amount DECIMAL(19, 2) NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    idempotency_key VARCHAR(36) NOT NULL UNIQUE,
    reference TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT transfer_different_accounts CHECK (from_account_id != to_account_id)
);

CREATE INDEX idx_transfer_idempotency ON transfer(idempotency_key);
CREATE INDEX idx_transfer_from_account ON transfer(from_account_id);
CREATE INDEX idx_transfer_created_at ON transfer(created_at DESC);

-- Audit log table
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_customer_id BIGINT REFERENCES customer(id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    details TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_created_at ON audit_log(created_at DESC);
CREATE INDEX idx_audit_actor ON audit_log(actor_customer_id);
