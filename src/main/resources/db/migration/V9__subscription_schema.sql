CREATE TABLE mb_subscription (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_tenant_idempotency (tenant_id, idempotency_key),
    KEY idx_subscription_tenant_status (tenant_id, status, id),

    CONSTRAINT fk_subscription_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_plan
        FOREIGN KEY (plan_id) REFERENCES mb_plan(id) ON DELETE RESTRICT,
    CONSTRAINT chk_subscription_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'CANCEL_SCHEDULED', 'EXPIRED', 'CANCELED')),
    CONSTRAINT chk_subscription_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_subscription_period (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    subscription_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    price_id BIGINT NOT NULL,
    price_version_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    base_amount_minor BIGINT NOT NULL,
    active_from DATETIME(6) NOT NULL,
    period_start DATETIME(6) NOT NULL,
    period_end DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_period_start (subscription_id, period_start),
    KEY idx_subscription_period_tenant_window
        (tenant_id, subscription_id, period_start, period_end, status),

    CONSTRAINT fk_subscription_period_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_period_subscription
        FOREIGN KEY (subscription_id) REFERENCES mb_subscription(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_period_plan
        FOREIGN KEY (plan_id) REFERENCES mb_plan(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_period_price
        FOREIGN KEY (price_id) REFERENCES mb_price(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_period_price_version
        FOREIGN KEY (price_version_id) REFERENCES mb_price_version(id) ON DELETE RESTRICT,
    CONSTRAINT chk_subscription_period_window CHECK (period_end > period_start),
    CONSTRAINT chk_subscription_period_amount CHECK (base_amount_minor >= 0),
    CONSTRAINT chk_subscription_period_status CHECK (status IN ('ACTIVE', 'ENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_subscription_change (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    subscription_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    from_plan_id BIGINT NOT NULL,
    to_plan_id BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_change_idempotency
        (tenant_id, subscription_id, idempotency_key),
    KEY idx_subscription_change_status (subscription_id, status, effective_at),

    CONSTRAINT fk_subscription_change_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_change_subscription
        FOREIGN KEY (subscription_id) REFERENCES mb_subscription(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_change_from_plan
        FOREIGN KEY (from_plan_id) REFERENCES mb_plan(id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscription_change_to_plan
        FOREIGN KEY (to_plan_id) REFERENCES mb_plan(id) ON DELETE RESTRICT,
    CONSTRAINT chk_subscription_change_plans CHECK (from_plan_id <> to_plan_id),
    CONSTRAINT chk_subscription_change_status
        CHECK (status IN ('SCHEDULED', 'APPLIED', 'CANCELED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
