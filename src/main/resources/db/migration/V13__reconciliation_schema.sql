CREATE TABLE mb_reconciliation_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_reconciliation_run_tenant_idempotency (tenant_id, idempotency_key),
    KEY idx_reconciliation_run_tenant_started (tenant_id, started_at, id),
    CONSTRAINT fk_reconciliation_run_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT chk_reconciliation_run_status CHECK (status IN ('RUNNING', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_reconciliation_difference (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    difference_type VARCHAR(48) NOT NULL,
    reference_type VARCHAR(32) NOT NULL,
    reference_id VARCHAR(96) NOT NULL,
    currency CHAR(3) NULL,
    expected_amount_minor BIGINT NULL,
    actual_amount_minor BIGINT NULL,
    expected_status VARCHAR(24) NULL,
    actual_status VARCHAR(24) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    evidence TEXT NOT NULL,
    resolution_note VARCHAR(1000) NULL,
    resolved_by BIGINT NULL,
    first_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_reconciliation_difference_fingerprint (tenant_id, fingerprint),
    KEY idx_reconciliation_difference_tenant_status (tenant_id, status, id),
    CONSTRAINT fk_reconciliation_difference_tenant
        FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id) ON DELETE RESTRICT,
    CONSTRAINT chk_reconciliation_difference_status
        CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'WAIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_reconciliation_run_difference (
    run_id BIGINT NOT NULL,
    difference_id BIGINT NOT NULL,
    observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (run_id, difference_id),
    KEY idx_reconciliation_run_difference_difference (difference_id, run_id),
    CONSTRAINT fk_reconciliation_run_difference_run
        FOREIGN KEY (run_id) REFERENCES mb_reconciliation_run(id) ON DELETE RESTRICT,
    CONSTRAINT fk_reconciliation_run_difference_difference
        FOREIGN KEY (difference_id) REFERENCES mb_reconciliation_difference(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mb_reconciliation_difference_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    difference_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    previous_status VARCHAR(20) NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    KEY idx_reconciliation_difference_action (difference_id, id),
    CONSTRAINT fk_reconciliation_difference_action_difference
        FOREIGN KEY (difference_id) REFERENCES mb_reconciliation_difference(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
