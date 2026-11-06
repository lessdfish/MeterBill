ALTER TABLE mb_outbox
    ADD COLUMN claim_token CHAR(36) NULL AFTER lease_until,
    ADD KEY idx_outbox_claim_token (claim_token);
