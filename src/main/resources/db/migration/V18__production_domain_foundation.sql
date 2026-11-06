CREATE TABLE mb_customer_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    external_key VARCHAR(128) NOT NULL,
    name VARCHAR(160) NOT NULL,
    email VARCHAR(190) NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    time_zone_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_account_external (tenant_id, external_key),
    KEY idx_customer_account_tenant_status (tenant_id, status, id),
    CONSTRAINT fk_customer_account_tenant FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT chk_customer_account_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_customer_account_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO mb_customer_account
    (tenant_id, external_key, name, currency, time_zone_id, status, is_default)
SELECT id, CONCAT('default-', tenant_code), CONCAT(name, ' 默认客户'), 'CNY', billing_zone_id, 'ACTIVE', TRUE
FROM mb_tenant;

ALTER TABLE mb_subscription ADD COLUMN account_id BIGINT NULL AFTER tenant_id;
UPDATE mb_subscription s
JOIN mb_customer_account a ON a.tenant_id = s.tenant_id AND a.is_default = TRUE
SET s.account_id = a.id;
ALTER TABLE mb_subscription MODIFY COLUMN account_id BIGINT NOT NULL;
ALTER TABLE mb_subscription ADD KEY idx_subscription_account_status (tenant_id, account_id, status, id);
ALTER TABLE mb_subscription ADD CONSTRAINT fk_subscription_account
    FOREIGN KEY (account_id) REFERENCES mb_customer_account(id) ON DELETE RESTRICT;

ALTER TABLE mb_invoice ADD COLUMN account_id BIGINT NULL AFTER tenant_id;
UPDATE mb_invoice i JOIN mb_subscription s ON s.id = i.subscription_id
SET i.account_id = s.account_id;
ALTER TABLE mb_invoice MODIFY COLUMN account_id BIGINT NOT NULL;
ALTER TABLE mb_invoice ADD KEY idx_invoice_account_status (tenant_id, account_id, status, id);
ALTER TABLE mb_invoice ADD CONSTRAINT fk_invoice_account
    FOREIGN KEY (account_id) REFERENCES mb_customer_account(id) ON DELETE RESTRICT;

ALTER TABLE mb_payment ADD COLUMN account_id BIGINT NULL AFTER tenant_id;
UPDATE mb_payment p JOIN mb_invoice i ON i.id = p.invoice_id
SET p.account_id = i.account_id;
ALTER TABLE mb_payment MODIFY COLUMN account_id BIGINT NOT NULL;
ALTER TABLE mb_payment ADD KEY idx_payment_account_status (tenant_id, account_id, status, id);
ALTER TABLE mb_payment ADD CONSTRAINT fk_payment_account
    FOREIGN KEY (account_id) REFERENCES mb_customer_account(id) ON DELETE RESTRICT;

ALTER TABLE mb_refund ADD COLUMN account_id BIGINT NULL AFTER tenant_id;
UPDATE mb_refund r JOIN mb_payment p ON p.id = r.payment_id
SET r.account_id = p.account_id;
ALTER TABLE mb_refund MODIFY COLUMN account_id BIGINT NOT NULL;
ALTER TABLE mb_refund ADD KEY idx_refund_account_status (tenant_id, account_id, status, id);
ALTER TABLE mb_refund ADD CONSTRAINT fk_refund_account
    FOREIGN KEY (account_id) REFERENCES mb_customer_account(id) ON DELETE RESTRICT;

ALTER TABLE mb_invoice_line ADD COLUMN account_id BIGINT NULL AFTER invoice_id;
UPDATE mb_invoice_line l JOIN mb_invoice i ON i.id = l.invoice_id
SET l.account_id = i.account_id;
ALTER TABLE mb_invoice_line MODIFY COLUMN account_id BIGINT NOT NULL;
ALTER TABLE mb_invoice_line ADD KEY idx_invoice_item_account (account_id, invoice_id, line_no);
ALTER TABLE mb_invoice_line ADD CONSTRAINT fk_invoice_item_account
    FOREIGN KEY (account_id) REFERENCES mb_customer_account(id) ON DELETE RESTRICT;
ALTER TABLE mb_invoice_line DROP CHECK chk_invoice_line_type;
ALTER TABLE mb_invoice_line ADD CONSTRAINT chk_invoice_line_type
    CHECK (line_type IN ('BASE', 'USAGE', 'EXTERNAL_CHARGE', 'TAX', 'CREDIT', 'ITEM_ADJUSTMENT'));

CREATE TABLE mb_subscription_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    subscription_id BIGINT NOT NULL,
    event_key VARCHAR(190) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    from_status VARCHAR(24) NULL,
    to_status VARCHAR(24) NULL,
    from_plan_id BIGINT NULL,
    to_plan_id BIGINT NULL,
    requested_at DATETIME(6) NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    metadata_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_event_key (tenant_id, subscription_id, event_key),
    KEY idx_subscription_event_timeline (tenant_id, subscription_id, effective_at, id),
    CONSTRAINT fk_subscription_event_tenant FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_event_account FOREIGN KEY (account_id) REFERENCES mb_customer_account(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_event_subscription FOREIGN KEY (subscription_id) REFERENCES mb_subscription(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO mb_subscription_event
    (tenant_id, account_id, subscription_id, event_key, event_type, to_status,
     to_plan_id, requested_at, effective_at, metadata_json)
SELECT tenant_id, account_id, id, CONCAT('migration:create:', id), 'CREATED', status,
       plan_id, created_at, created_at, JSON_OBJECT('source', 'V18_BACKFILL')
FROM mb_subscription;

CREATE TABLE mb_domain_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    aggregate_version BIGINT NULL,
    event_type VARCHAR(80) NOT NULL,
    event_key VARCHAR(190) NOT NULL,
    payload_json JSON NOT NULL,
    correlation_id VARCHAR(128) NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_domain_event_key (tenant_id, event_key),
    KEY idx_domain_event_aggregate (tenant_id, aggregate_type, aggregate_id, id),
    KEY idx_domain_event_time (tenant_id, occurred_at, id),
    CONSTRAINT fk_domain_event_tenant FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_domain_event_account FOREIGN KEY (account_id) REFERENCES mb_customer_account(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_payment_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    payment_id BIGINT NULL,
    refund_id BIGINT NULL,
    sequence_no INT NOT NULL,
    transaction_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    external_reference VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    event_key VARCHAR(190) NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_transaction_event (tenant_id, event_key),
    UNIQUE KEY uk_payment_transaction_payment_sequence (payment_id, sequence_no),
    UNIQUE KEY uk_payment_transaction_refund_sequence (refund_id, sequence_no),
    KEY idx_payment_transaction_payment (tenant_id, payment_id, id),
    KEY idx_payment_transaction_refund (tenant_id, refund_id, id),
    CONSTRAINT fk_payment_transaction_tenant FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_transaction_account FOREIGN KEY (account_id) REFERENCES mb_customer_account(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_transaction_payment FOREIGN KEY (payment_id) REFERENCES mb_payment(id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_transaction_refund FOREIGN KEY (refund_id) REFERENCES mb_refund(id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_transaction_owner CHECK ((payment_id IS NOT NULL AND refund_id IS NULL) OR (payment_id IS NULL AND refund_id IS NOT NULL)),
    CONSTRAINT chk_payment_transaction_type CHECK (transaction_type IN ('PURCHASE', 'REFUND', 'REVERSAL')),
    CONSTRAINT chk_payment_transaction_status CHECK (status IN ('PENDING', 'UNKNOWN', 'SUCCEEDED', 'FAILED', 'REVERSED')),
    CONSTRAINT chk_payment_transaction_amount CHECK (amount_minor > 0),
    CONSTRAINT chk_payment_transaction_sequence CHECK (sequence_no > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO mb_payment_transaction
    (tenant_id, account_id, payment_id, sequence_no, transaction_type, status,
     external_reference, currency, amount_minor, event_key, effective_at)
SELECT tenant_id, account_id, id, 1, 'PURCHASE', status,
       external_reference, currency, amount_minor, CONCAT('migration:payment:', id),
       COALESCE(completed_at, created_at)
FROM mb_payment;

INSERT INTO mb_payment_transaction
    (tenant_id, account_id, refund_id, sequence_no, transaction_type, status,
     external_reference, currency, amount_minor, event_key, effective_at)
SELECT tenant_id, account_id, id, 1, 'REFUND', status,
       external_reference, currency, amount_minor, CONCAT('migration:refund:', id),
       COALESCE(completed_at, created_at)
FROM mb_refund;

INSERT IGNORE INTO mb_role_permission (role_id, permission_code)
SELECT id, 'ACCOUNT_MANAGE' FROM mb_role WHERE role_code IN ('OWNER', 'ADMIN');
