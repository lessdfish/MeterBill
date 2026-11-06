-- Loaded only by the perf Spring profile. It is intentionally isolated from demo data.
CREATE TABLE mb_perf_seed_number (n INT NOT NULL PRIMARY KEY);

INSERT INTO mb_perf_seed_number(n)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 500
)
SELECT n FROM seq;

INSERT INTO mb_product(id, product_code, name, description, status)
VALUES (910001, 'PERF_LLM_API', '性能测试大模型 API', '仅用于独立性能环境', 'ACTIVE');

INSERT INTO mb_plan(id, product_id, plan_code, name, description, status, version)
VALUES (910001, 910001, 'PERF_STANDARD', '性能标准套餐', '固定性能数据套餐', 'ACTIVE', 1);

INSERT INTO mb_price(id, plan_id, currency, billing_period)
VALUES (910001, 910001, 'CNY', 'MONTHLY');

INSERT INTO mb_price_version(id, price_id, version_no, base_amount_minor, status, effective_at, revision)
VALUES (910001, 910001, 1, 0, 'PUBLISHED', DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 YEAR), 1);

INSERT INTO mb_usage_meter(id, product_id, meter_code, name, unit)
VALUES
    (910001, 910001, 'INPUT_TOKENS', '输入 Token', 'TOKEN'),
    (910002, 910001, 'OUTPUT_TOKENS', '输出 Token', 'TOKEN');

INSERT INTO mb_usage_price_rule
    (id, price_version_id, meter_id, pricing_mode, included_quantity, unit_amount_minor)
VALUES
    (910001, 910001, 910001, 'PER_UNIT', 0, 1),
    (910002, 910001, 910002, 'PER_UNIT', 0, 1);

INSERT INTO mb_ai_model(id, product_id, model_code, name)
VALUES (910001, 910001, 'PERF-MODEL', '性能测试模型');

INSERT INTO mb_model_meter(model_id, meter_id)
VALUES (910001, 910001), (910001, 910002);

INSERT INTO mb_tenant(id, tenant_code, name, billing_zone_id, status)
SELECT 910000 + n, CONCAT('PERF_TENANT_', LPAD(n, 3, '0')),
       CONCAT('性能租户 ', LPAD(n, 3, '0')), 'Asia/Shanghai', 'ACTIVE'
FROM mb_perf_seed_number WHERE n <= 100;

INSERT INTO mb_customer_account
    (id, tenant_id, external_key, name, email, currency, time_zone_id, status, is_default)
SELECT 910000 + n, 910000 + n, CONCAT('perf-account-', LPAD(n, 3, '0')),
       CONCAT('性能客户 ', LPAD(n, 3, '0')),
       CONCAT('billing-', LPAD(n, 3, '0'), '@perf.invalid'),
       'CNY', 'Asia/Shanghai', 'ACTIVE', TRUE
FROM mb_perf_seed_number WHERE n <= 100;

INSERT INTO mb_subscription
    (id, tenant_id, account_id, plan_id, idempotency_key, request_hash, status, version)
SELECT 920000 + n,
       910000 + FLOOR((n - 1) / 5) + 1,
       910000 + FLOOR((n - 1) / 5) + 1,
       910001,
       CONCAT('perf-subscription-', LPAD(n, 3, '0')),
       LPAD(n, 64, '0'), 'ACTIVE', 1
FROM mb_perf_seed_number;

INSERT INTO mb_subscription_period
    (id, tenant_id, subscription_id, plan_id, price_id, price_version_id, currency,
     base_amount_minor, active_from, period_start, period_end, status)
SELECT 930000 + n,
       910000 + FLOOR((n - 1) / 5) + 1,
       920000 + n, 910001, 910001, 910001, 'CNY', 0,
       DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'),
       DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'),
       DATE_ADD(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 1 MONTH),
       'ACTIVE'
FROM mb_perf_seed_number;

DROP TABLE mb_perf_seed_number;
