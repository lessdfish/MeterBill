ALTER TABLE mb_usage_period_total
    ADD COLUMN shard_no SMALLINT NOT NULL DEFAULT 0 AFTER meter_id,
    DROP INDEX uk_usage_total_period_meter,
    ADD UNIQUE KEY uk_usage_total_period_meter_shard
        (tenant_id, subscription_id, period_id, meter_id, shard_no),
    ADD CONSTRAINT chk_usage_total_shard
        CHECK (shard_no BETWEEN 0 AND 31);
