CREATE TABLE mb_ledger_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    account_code VARCHAR(32) NOT NULL,
    account_type VARCHAR(16) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_account_dimension (tenant_id, account_code, currency),
    CONSTRAINT fk_ledger_account_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT chk_ledger_account_type
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT chk_ledger_account_status CHECK (status IN ('ACTIVE', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_ledger_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_id BIGINT NOT NULL,
    posting_action VARCHAR(32) NOT NULL,
    currency CHAR(3) NOT NULL,
    description VARCHAR(200) NOT NULL,
    debit_total_minor BIGINT NOT NULL,
    credit_total_minor BIGINT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_ledger_business_posting
        (tenant_id, business_type, business_id, posting_action),
    KEY idx_ledger_tenant_created (tenant_id, created_at, id),
    CONSTRAINT fk_ledger_transaction_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT chk_ledger_totals
        CHECK (debit_total_minor > 0 AND debit_total_minor = credit_total_minor),
    CONSTRAINT chk_ledger_transaction_status CHECK (status = 'POSTED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_ledger_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount_minor BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    KEY idx_ledger_entry_transaction (transaction_id, id),
    KEY idx_ledger_entry_account (account_id, id),
    CONSTRAINT fk_ledger_entry_transaction
        FOREIGN KEY (transaction_id) REFERENCES mb_ledger_transaction(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_entry_account
        FOREIGN KEY (account_id) REFERENCES mb_ledger_account(id) ON DELETE RESTRICT,
    CONSTRAINT chk_ledger_entry_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entry_amount CHECK (amount_minor > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_payment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    invoice_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    external_reference VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_tenant_idempotency (tenant_id, idempotency_key),
    UNIQUE KEY uk_payment_external_reference (external_reference),
    KEY idx_payment_invoice_status (tenant_id, invoice_id, status, id),
    CONSTRAINT fk_payment_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_invoice
        FOREIGN KEY (invoice_id) REFERENCES mb_invoice(id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    external_reference VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_tenant_idempotency (tenant_id, idempotency_key),
    UNIQUE KEY uk_refund_external_reference (external_reference),
    KEY idx_refund_payment_status (tenant_id, payment_id, status, id),
    CONSTRAINT fk_refund_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_refund_payment
        FOREIGN KEY (payment_id) REFERENCES mb_payment(id) ON DELETE RESTRICT,
    CONSTRAINT chk_refund_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_refund_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_channel_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    payment_id BIGINT NULL,
    refund_id BIGINT NULL,
    channel_code VARCHAR(24) NOT NULL DEFAULT 'SIMULATED',
    external_reference VARCHAR(64) NOT NULL,
    transaction_type VARCHAR(16) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_channel_external_reference (channel_code, external_reference),
    UNIQUE KEY uk_channel_payment_type (payment_id, transaction_type),
    UNIQUE KEY uk_channel_refund_type (refund_id, transaction_type),
    KEY idx_channel_tenant_status (tenant_id, status, id),
    CONSTRAINT fk_channel_transaction_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_channel_transaction_payment
        FOREIGN KEY (payment_id) REFERENCES mb_payment(id) ON DELETE RESTRICT,
    CONSTRAINT fk_channel_transaction_refund
        FOREIGN KEY (refund_id) REFERENCES mb_refund(id) ON DELETE RESTRICT,
    CONSTRAINT chk_channel_transaction_owner
        CHECK ((payment_id IS NOT NULL AND refund_id IS NULL)
            OR (payment_id IS NULL AND refund_id IS NOT NULL)),
    CONSTRAINT chk_channel_transaction_type CHECK (transaction_type IN ('CHARGE', 'REFUND')),
    CONSTRAINT chk_channel_transaction_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_channel_transaction_status
        CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
