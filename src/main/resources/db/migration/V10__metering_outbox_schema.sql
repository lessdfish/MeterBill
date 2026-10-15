CREATE TABLE mb_usage_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    subscription_id BIGINT NOT NULL,
    period_id BIGINT NOT NULL,
    meter_id BIGINT NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    model_code VARCHAR(64) NOT NULL,
    quantity BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RECEIVED',
    delivery_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    processed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_event_tenant_source_event (tenant_id, source_id, event_id),
    KEY idx_usage_event_period_status (tenant_id, subscription_id, period_id, status, id),
    KEY idx_usage_event_tenant_time (tenant_id, subscription_id, occurred_at, id),

    CONSTRAINT fk_usage_event_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_event_subscription
        FOREIGN KEY (subscription_id) REFERENCES mb_subscription(id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_event_period
        FOREIGN KEY (period_id) REFERENCES mb_subscription_period(id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_event_meter
        FOREIGN KEY (meter_id) REFERENCES mb_usage_meter(id) ON DELETE RESTRICT,
    CONSTRAINT chk_usage_event_quantity CHECK (quantity > 0),
    CONSTRAINT chk_usage_event_delivery_count CHECK (delivery_count >= 0),
    CONSTRAINT chk_usage_event_status
        CHECK (status IN ('RECEIVED', 'RETRYABLE_FAILED', 'PROCESSED', 'FINAL_FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_usage_event_processed (
    usage_event_id BIGINT NOT NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (usage_event_id),
    CONSTRAINT fk_usage_event_processed_event
        FOREIGN KEY (usage_event_id) REFERENCES mb_usage_event(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_usage_period_total (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    subscription_id BIGINT NOT NULL,
    period_id BIGINT NOT NULL,
    meter_id BIGINT NOT NULL,
    total_quantity BIGINT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_usage_total_period_meter (tenant_id, subscription_id, period_id, meter_id),
    KEY idx_usage_total_subscription_period (tenant_id, subscription_id, period_id),

    CONSTRAINT fk_usage_total_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_total_subscription
        FOREIGN KEY (subscription_id) REFERENCES mb_subscription(id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_total_period
        FOREIGN KEY (period_id) REFERENCES mb_subscription_period(id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_total_meter
        FOREIGN KEY (meter_id) REFERENCES mb_usage_meter(id) ON DELETE RESTRICT,
    CONSTRAINT chk_usage_total_quantity CHECK (total_quantity >= 0),
    CONSTRAINT chk_usage_total_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL,
    event_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_until DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_type_event (event_type, event_id),
    KEY idx_outbox_dispatch (status, next_retry_at, id),

    CONSTRAINT fk_outbox_usage_event
        FOREIGN KEY (event_id) REFERENCES mb_usage_event(id) ON DELETE RESTRICT,
    CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_outbox_status
        CHECK (status IN ('READY', 'PUBLISHING', 'PUBLISHED', 'RETRYABLE_FAILED', 'FINAL_FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
