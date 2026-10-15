CREATE TABLE mb_invoice (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    subscription_id BIGINT NOT NULL,
    period_id BIGINT NOT NULL,
    price_version_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    period_start DATETIME(6) NOT NULL,
    period_end DATETIME(6) NOT NULL,
    base_amount_minor BIGINT NOT NULL,
    usage_amount_minor BIGINT NOT NULL,
    total_amount_minor BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ISSUED',
    issued_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_tenant_subscription_period (tenant_id, subscription_id, period_id),
    KEY idx_invoice_tenant_status (tenant_id, status, id),
    KEY idx_invoice_tenant_issued (tenant_id, issued_at, id),

    CONSTRAINT fk_invoice_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_subscription
        FOREIGN KEY (subscription_id) REFERENCES mb_subscription(id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_period
        FOREIGN KEY (period_id) REFERENCES mb_subscription_period(id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_price_version
        FOREIGN KEY (price_version_id) REFERENCES mb_price_version(id) ON DELETE RESTRICT,
    CONSTRAINT chk_invoice_period CHECK (period_end > period_start),
    CONSTRAINT chk_invoice_amounts
        CHECK (base_amount_minor >= 0 AND usage_amount_minor >= 0 AND total_amount_minor >= 0),
    CONSTRAINT chk_invoice_status
        CHECK (status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOIDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_invoice_line (
    id BIGINT NOT NULL AUTO_INCREMENT,
    invoice_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    line_type VARCHAR(16) NOT NULL,
    meter_id BIGINT NULL,
    description VARCHAR(160) NOT NULL,
    pricing_mode VARCHAR(20) NULL,
    quantity BIGINT NOT NULL DEFAULT 0,
    included_quantity BIGINT NOT NULL DEFAULT 0,
    billable_quantity BIGINT NOT NULL DEFAULT 0,
    amount_minor BIGINT NOT NULL,
    rate_snapshot TEXT NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_line_number (invoice_id, line_no),
    UNIQUE KEY uk_invoice_line_meter (invoice_id, line_type, meter_id),
    CONSTRAINT fk_invoice_line_invoice
        FOREIGN KEY (invoice_id) REFERENCES mb_invoice(id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_line_meter
        FOREIGN KEY (meter_id) REFERENCES mb_usage_meter(id) ON DELETE RESTRICT,
    CONSTRAINT chk_invoice_line_type CHECK (line_type IN ('BASE', 'USAGE')),
    CONSTRAINT chk_invoice_line_quantities
        CHECK (quantity >= 0 AND included_quantity >= 0 AND billable_quantity >= 0),
    CONSTRAINT chk_invoice_line_amount CHECK (amount_minor >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_invoice_adjustment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    invoice_id BIGINT NOT NULL,
    period_id BIGINT NOT NULL,
    adjustment_no INT NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    source_event_count INT NOT NULL,
    reason VARCHAR(32) NOT NULL DEFAULT 'LATE_USAGE',
    status VARCHAR(24) NOT NULL DEFAULT 'ISSUED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_adjustment_invoice_number (invoice_id, adjustment_no),
    KEY idx_adjustment_tenant_period (tenant_id, period_id, id),
    CONSTRAINT fk_adjustment_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_adjustment_invoice
        FOREIGN KEY (invoice_id) REFERENCES mb_invoice(id) ON DELETE RESTRICT,
    CONSTRAINT fk_adjustment_period
        FOREIGN KEY (period_id) REFERENCES mb_subscription_period(id) ON DELETE RESTRICT,
    CONSTRAINT chk_adjustment_number CHECK (adjustment_no > 0),
    CONSTRAINT chk_adjustment_event_count CHECK (source_event_count > 0),
    CONSTRAINT chk_adjustment_reason CHECK (reason IN ('LATE_USAGE', 'MANUAL_CREDIT', 'MANUAL_DEBIT')),
    CONSTRAINT chk_adjustment_status
        CHECK (status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOIDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_invoice_adjustment_line (
    id BIGINT NOT NULL AUTO_INCREMENT,
    adjustment_id BIGINT NOT NULL,
    line_no INT NOT NULL,
    meter_id BIGINT NOT NULL,
    description VARCHAR(160) NOT NULL,
    pricing_mode VARCHAR(20) NOT NULL,
    quantity BIGINT NOT NULL,
    included_quantity BIGINT NOT NULL,
    billable_quantity BIGINT NOT NULL,
    amount_minor BIGINT NOT NULL,
    rate_snapshot TEXT NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_adjustment_line_number (adjustment_id, line_no),
    UNIQUE KEY uk_adjustment_line_meter (adjustment_id, meter_id),
    CONSTRAINT fk_adjustment_line_adjustment
        FOREIGN KEY (adjustment_id) REFERENCES mb_invoice_adjustment(id) ON DELETE RESTRICT,
    CONSTRAINT fk_adjustment_line_meter
        FOREIGN KEY (meter_id) REFERENCES mb_usage_meter(id) ON DELETE RESTRICT,
    CONSTRAINT chk_adjustment_line_quantities
        CHECK (quantity >= 0 AND included_quantity >= 0 AND billable_quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_invoice_event (
    usage_event_id BIGINT NOT NULL,
    invoice_id BIGINT NULL,
    adjustment_id BIGINT NULL,
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (usage_event_id),
    KEY idx_invoice_event_invoice (invoice_id, usage_event_id),
    KEY idx_invoice_event_adjustment (adjustment_id, usage_event_id),
    CONSTRAINT fk_invoice_event_usage_event
        FOREIGN KEY (usage_event_id) REFERENCES mb_usage_event(id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_event_invoice
        FOREIGN KEY (invoice_id) REFERENCES mb_invoice(id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_event_adjustment
        FOREIGN KEY (adjustment_id) REFERENCES mb_invoice_adjustment(id) ON DELETE RESTRICT,
    CONSTRAINT chk_invoice_event_assignment
        CHECK ((invoice_id IS NOT NULL AND adjustment_id IS NULL)
            OR (invoice_id IS NULL AND adjustment_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
