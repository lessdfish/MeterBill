-- Replace the legacy payment reversal terminology without modifying historical migrations.
ALTER TABLE mb_payment DROP CHECK chk_payment_status;
ALTER TABLE mb_channel_transaction DROP CHECK chk_channel_transaction_status;
ALTER TABLE mb_payment_transaction DROP CHECK chk_payment_transaction_type;
ALTER TABLE mb_payment_transaction DROP CHECK chk_payment_transaction_status;

UPDATE mb_payment SET status = 'REVOKED' WHERE status = 'REVERSED';
UPDATE mb_channel_transaction SET status = 'REVOKED' WHERE status = 'REVERSED';
UPDATE mb_payment_transaction
SET transaction_type = 'REVOCATION',
    status = 'REVOKED',
    event_key = REPLACE(event_key, ':reversed', ':revoked')
WHERE transaction_type = 'REVERSAL' OR status = 'REVERSED';

UPDATE mb_ledger_transaction
SET business_type = 'PAYMENT_REVOCATION', posting_action = 'REVOKE'
WHERE business_type = 'PAYMENT_REVERSAL';

UPDATE mb_domain_event
SET event_type = 'PAYMENT_REVOKED',
    event_key = REPLACE(event_key, ':reversed', ':revoked')
WHERE event_type = 'PAYMENT_REVERSED';

UPDATE mb_security_audit
SET action = 'PAYMENT_REVOKE'
WHERE action = 'PAYMENT_REVERSE';

ALTER TABLE mb_payment
    ADD CONSTRAINT chk_payment_status
        CHECK (status IN ('PENDING','SUCCEEDED','FAILED','UNKNOWN','REVOKED'));
ALTER TABLE mb_channel_transaction
    ADD CONSTRAINT chk_channel_transaction_status
        CHECK (status IN ('PENDING','SUCCEEDED','FAILED','UNKNOWN','REVOKED'));
ALTER TABLE mb_payment_transaction
    ADD CONSTRAINT chk_payment_transaction_type
        CHECK (transaction_type IN ('PURCHASE','REFUND','REVOCATION')),
    ADD CONSTRAINT chk_payment_transaction_status
        CHECK (status IN ('PENDING','UNKNOWN','SUCCEEDED','FAILED','REVOKED'));

RENAME TABLE mb_payment_reversal TO mb_payment_revocation;
ALTER TABLE mb_payment_revocation
    DROP FOREIGN KEY fk_reversal_payment,
    DROP FOREIGN KEY fk_reversal_original,
    DROP FOREIGN KEY fk_reversal_transaction,
    RENAME INDEX uk_payment_reversal TO uk_payment_revocation,
    CHANGE COLUMN reversal_transaction_id revocation_transaction_id BIGINT NOT NULL,
    ADD CONSTRAINT fk_revocation_payment
        FOREIGN KEY (payment_id) REFERENCES mb_payment(id),
    ADD CONSTRAINT fk_revocation_original
        FOREIGN KEY (original_transaction_id) REFERENCES mb_ledger_transaction(id),
    ADD CONSTRAINT fk_revocation_transaction
        FOREIGN KEY (revocation_transaction_id) REFERENCES mb_ledger_transaction(id);
