-- Channel facts may exist without an internal order (e.g. a lost local intent).
ALTER TABLE mb_channel_transaction DROP CHECK chk_channel_transaction_owner;
ALTER TABLE mb_channel_transaction ADD CONSTRAINT chk_channel_transaction_owner
    CHECK (payment_id IS NULL OR refund_id IS NULL);
ALTER TABLE mb_channel_transaction DROP CHECK chk_channel_transaction_status;
ALTER TABLE mb_channel_transaction ADD CONSTRAINT chk_channel_transaction_status
    CHECK (status IN ('PENDING','SUCCEEDED','FAILED','UNKNOWN','REVERSED'));
ALTER TABLE mb_payment DROP CHECK chk_payment_status;
ALTER TABLE mb_payment ADD CONSTRAINT chk_payment_status
    CHECK (status IN ('PENDING','SUCCEEDED','FAILED','UNKNOWN','REVERSED'));
CREATE TABLE mb_payment_reversal (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    original_transaction_id BIGINT NOT NULL,
    reversal_transaction_id BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    actor_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_payment_reversal(payment_id),
    CONSTRAINT fk_reversal_payment FOREIGN KEY(payment_id) REFERENCES mb_payment(id),
    CONSTRAINT fk_reversal_original FOREIGN KEY(original_transaction_id) REFERENCES mb_ledger_transaction(id),
    CONSTRAINT fk_reversal_transaction FOREIGN KEY(reversal_transaction_id) REFERENCES mb_ledger_transaction(id)
);
