-- Loaded only by the demo Spring profile. IDs and business keys are intentionally stable.
INSERT INTO mb_tenant (id, tenant_code, name, billing_zone_id, status)
VALUES (900001, 'YUNZHI_AI', '云智 AI 开放平台', 'Asia/Shanghai', 'ACTIVE');

INSERT INTO mb_customer_account
    (id, tenant_id, external_key, name, email, currency, time_zone_id, status, is_default)
VALUES
    (900001, 900001, 'yunzhi-main', '云智 AI 企业客户', 'billing@yunzhi.example', 'CNY', 'Asia/Shanghai', 'ACTIVE', TRUE);

INSERT INTO mb_product (id, product_code, name, description, status)
VALUES (900001, 'YUNZHI_LLM_API', '云智大模型 API', '用于固定演示的输入、输出 Token 计量产品', 'ACTIVE');

INSERT INTO mb_plan (id, product_id, plan_code, name, description, status, version)
VALUES
    (900001, 900001, 'BASIC', '基础版', '适合小规模 API 调用', 'ACTIVE', 1),
    (900002, 900001, 'ENTERPRISE', '企业版', '适合企业级 API 调用', 'ACTIVE', 1);

INSERT INTO mb_price (id, plan_id, currency, billing_period)
VALUES
    (900001, 900001, 'CNY', 'MONTHLY'),
    (900002, 900002, 'CNY', 'MONTHLY');

INSERT INTO mb_price_version
    (id, price_id, version_no, base_amount_minor, status, effective_at, revision)
VALUES
    (900001, 900001, 1, 9900, 'PUBLISHED', DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 YEAR), 1),
    (900002, 900002, 1, 19900, 'PUBLISHED', DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 YEAR), 1);

INSERT INTO mb_usage_meter (id, product_id, meter_code, name, unit)
VALUES
    (900001, 900001, 'INPUT_TOKENS', '输入 Token', 'TOKEN'),
    (900002, 900001, 'OUTPUT_TOKENS', '输出 Token', 'TOKEN');

INSERT INTO mb_usage_price_rule
    (id, price_version_id, meter_id, pricing_mode, included_quantity, unit_amount_minor)
VALUES
    (900001, 900001, 900001, 'PER_UNIT', 10000, 1),
    (900002, 900001, 900002, 'PER_UNIT', 2000, 2),
    (900003, 900002, 900001, 'PER_UNIT', 100000, 1),
    (900004, 900002, 900002, 'PER_UNIT', 20000, 1);

INSERT INTO mb_ai_model (id, product_id, model_code, name)
VALUES (900001, 900001, 'YUNZHI-GPT', '云智 GPT 演示模型');
INSERT INTO mb_model_meter (model_id, meter_id)
VALUES (900001, 900001), (900001, 900002);

INSERT INTO mb_subscription
    (id, tenant_id, account_id, plan_id, idempotency_key, request_hash, status, version)
VALUES
    (900001, 900001, 900001, 900001, 'demo-current', REPEAT('1', 64), 'ACTIVE', 1),
    (900002, 900001, 900001, 900001, 'demo-paid', REPEAT('2', 64), 'EXPIRED', 1),
    (900003, 900001, 900001, 900001, 'demo-unknown', REPEAT('3', 64), 'EXPIRED', 1),
    (900004, 900001, 900001, 900001, 'demo-revoked', REPEAT('4', 64), 'EXPIRED', 1),
    (900005, 900001, 900001, 900002, 'demo-refunded', REPEAT('5', 64), 'EXPIRED', 1);

INSERT INTO mb_subscription_period
    (id, tenant_id, subscription_id, plan_id, price_id, price_version_id, currency,
     base_amount_minor, active_from, period_start, period_end, status)
VALUES
    (900001, 900001, 900001, 900001, 900001, 900001, 'CNY', 9900,
     DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'),
     DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'),
     DATE_ADD(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 1 MONTH), 'ACTIVE'),
    (900002, 900001, 900002, 900001, 900001, 900001, 'CNY', 9900,
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 4 MONTH),
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 4 MONTH),
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 3 MONTH), 'ENDED'),
    (900003, 900001, 900003, 900001, 900001, 900001, 'CNY', 9900,
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 3 MONTH),
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 3 MONTH),
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 2 MONTH), 'ENDED'),
    (900004, 900001, 900004, 900001, 900001, 900001, 'CNY', 9900,
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 2 MONTH),
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 2 MONTH),
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 1 MONTH), 'ENDED'),
    (900005, 900001, 900005, 900002, 900002, 900002, 'CNY', 19900,
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 1 MONTH),
     DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 1 MONTH),
     DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), 'ENDED');

INSERT INTO mb_subscription_event
    (id, tenant_id, account_id, subscription_id, event_key, event_type, to_status,
     to_plan_id, requested_at, effective_at, metadata_json)
SELECT id, tenant_id, account_id, id, CONCAT('demo:subscription:', id), 'CREATED', status,
       plan_id, created_at, created_at, JSON_OBJECT('source', 'FIXED_DEMO')
FROM mb_subscription WHERE tenant_id = 900001;

INSERT INTO mb_usage_event
    (id, tenant_id, subscription_id, period_id, meter_id, source_id, event_id, model_code,
     quantity, occurred_at, payload_hash, status, delivery_count, processed_at)
VALUES
    (900001, 900001, 900002, 900002, 900001, 'demo', 'usage-input-001', 'YUNZHI-GPT',
     12500, DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 4 MONTH) + INTERVAL 1 DAY,
     REPEAT('a', 64), 'PROCESSED', 1, UTC_TIMESTAMP(6)),
    (900002, 900001, 900002, 900002, 900002, 'demo', 'usage-output-001', 'YUNZHI-GPT',
     2500, DATE_SUB(DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-01 00:00:00'), INTERVAL 4 MONTH) + INTERVAL 1 DAY,
     REPEAT('b', 64), 'PROCESSED', 1, UTC_TIMESTAMP(6));

INSERT INTO mb_usage_event_processed (usage_event_id) VALUES (900001), (900002);
INSERT INTO mb_usage_period_total
    (id, tenant_id, subscription_id, period_id, meter_id, total_quantity, revision)
VALUES
    (900001, 900001, 900002, 900002, 900001, 12500, 1),
    (900002, 900001, 900002, 900002, 900002, 2500, 1);
INSERT INTO mb_outbox (id, event_type, event_id, status, attempt_count, published_at)
VALUES
    (900001, 'USAGE_EVENT_ACCEPTED', 900001, 'PUBLISHED', 1, UTC_TIMESTAMP(6)),
    (900002, 'USAGE_EVENT_ACCEPTED', 900002, 'PUBLISHED', 1, UTC_TIMESTAMP(6));

INSERT INTO mb_invoice
    (id, tenant_id, account_id, subscription_id, period_id, price_version_id, currency,
     period_start, period_end, base_amount_minor, usage_amount_minor, total_amount_minor, status)
VALUES
    (900002, 900001, 900001, 900002, 900002, 900001, 'CNY',
     (SELECT period_start FROM mb_subscription_period WHERE id=900002),
     (SELECT period_end FROM mb_subscription_period WHERE id=900002), 9900, 3500, 13400, 'PAID'),
    (900003, 900001, 900001, 900003, 900003, 900001, 'CNY',
     (SELECT period_start FROM mb_subscription_period WHERE id=900003),
     (SELECT period_end FROM mb_subscription_period WHERE id=900003), 9900, 0, 9900, 'ISSUED'),
    (900004, 900001, 900001, 900004, 900004, 900001, 'CNY',
     (SELECT period_start FROM mb_subscription_period WHERE id=900004),
     (SELECT period_end FROM mb_subscription_period WHERE id=900004), 9900, 0, 9900, 'ISSUED'),
    (900005, 900001, 900001, 900005, 900005, 900002, 'CNY',
     (SELECT period_start FROM mb_subscription_period WHERE id=900005),
     (SELECT period_end FROM mb_subscription_period WHERE id=900005), 19900, 0, 19900, 'PARTIALLY_PAID');

INSERT INTO mb_invoice_line
    (id, invoice_id, account_id, line_no, line_type, meter_id, description, pricing_mode,
     quantity, included_quantity, billable_quantity, amount_minor, rate_snapshot)
VALUES
    (900001, 900002, 900001, 1, 'BASE', NULL, '基础版月费', NULL, 0, 0, 0, 9900, '{"baseAmountMinor":9900}'),
    (900002, 900002, 900001, 2, 'USAGE', 900001, '输入 Token 用量', 'PER_UNIT', 12500, 10000, 2500, 2500, '{"unitAmountMinor":1}'),
    (900003, 900002, 900001, 3, 'USAGE', 900002, '输出 Token 用量', 'PER_UNIT', 2500, 2000, 500, 1000, '{"unitAmountMinor":2}'),
    (900004, 900003, 900001, 1, 'BASE', NULL, '基础版月费', NULL, 0, 0, 0, 9900, '{"baseAmountMinor":9900}'),
    (900005, 900004, 900001, 1, 'BASE', NULL, '基础版月费', NULL, 0, 0, 0, 9900, '{"baseAmountMinor":9900}'),
    (900006, 900005, 900001, 1, 'BASE', NULL, '企业版月费', NULL, 0, 0, 0, 19900, '{"baseAmountMinor":19900}');

INSERT INTO mb_invoice_event (usage_event_id, invoice_id)
VALUES (900001, 900002), (900002, 900002);

INSERT INTO mb_ledger_account (id, tenant_id, account_code, account_type, currency)
VALUES
    (900001, 900001, 'AR', 'ASSET', 'CNY'),
    (900002, 900001, 'REVENUE', 'REVENUE', 'CNY'),
    (900003, 900001, 'CASH', 'ASSET', 'CNY');

INSERT INTO mb_ledger_transaction
    (id, tenant_id, business_type, business_id, posting_action, currency, description,
     debit_total_minor, credit_total_minor, request_hash)
VALUES
    (900001,900001,'INVOICE',900002,'ISSUE','CNY','固定演示账单',13400,13400,REPEAT('1',64)),
    (900002,900001,'INVOICE',900003,'ISSUE','CNY','固定演示账单',9900,9900,REPEAT('2',64)),
    (900003,900001,'INVOICE',900004,'ISSUE','CNY','固定演示账单',9900,9900,REPEAT('3',64)),
    (900004,900001,'INVOICE',900005,'ISSUE','CNY','固定演示账单',19900,19900,REPEAT('4',64)),
    (900010,900001,'PAYMENT',900001,'SETTLE','CNY','支付成功',13400,13400,REPEAT('a',64)),
    (900011,900001,'PAYMENT',900003,'SETTLE','CNY','支付后撤销',9900,9900,REPEAT('b',64)),
    (900012,900001,'PAYMENT_REVOCATION',900003,'REVOKE','CNY','演示支付撤销',9900,9900,REPEAT('c',64)),
    (900013,900001,'PAYMENT',900004,'SETTLE','CNY','企业版支付',19900,19900,REPEAT('d',64)),
    (900014,900001,'REFUND',900001,'REFUND','CNY','演示退款',1000,1000,REPEAT('e',64));

INSERT INTO mb_ledger_entry (transaction_id, account_id, direction, amount_minor)
VALUES
    (900001,900001,'DEBIT',13400),(900001,900002,'CREDIT',13400),
    (900002,900001,'DEBIT',9900),(900002,900002,'CREDIT',9900),
    (900003,900001,'DEBIT',9900),(900003,900002,'CREDIT',9900),
    (900004,900001,'DEBIT',19900),(900004,900002,'CREDIT',19900),
    (900010,900003,'DEBIT',13400),(900010,900001,'CREDIT',13400),
    (900011,900003,'DEBIT',9900),(900011,900001,'CREDIT',9900),
    (900012,900001,'DEBIT',9900),(900012,900003,'CREDIT',9900),
    (900013,900003,'DEBIT',19900),(900013,900001,'CREDIT',19900),
    (900014,900001,'DEBIT',1000),(900014,900003,'CREDIT',1000);

INSERT INTO mb_payment
    (id, tenant_id, account_id, invoice_id, idempotency_key, request_hash, external_reference,
     currency, amount_minor, status, completed_at)
VALUES
    (900001,900001,900001,900002,'demo-pay-success',REPEAT('1',64),'DEMO-PAY-SUCCESS','CNY',13400,'SUCCEEDED',UTC_TIMESTAMP(6)),
    (900002,900001,900001,900003,'demo-pay-unknown',REPEAT('2',64),'DEMO-PAY-UNKNOWN','CNY',9900,'UNKNOWN',NULL),
    (900003,900001,900001,900004,'demo-pay-revoked',REPEAT('3',64),'DEMO-PAY-REVOKED','CNY',9900,'REVOKED',UTC_TIMESTAMP(6)),
    (900004,900001,900001,900005,'demo-pay-refund',REPEAT('4',64),'DEMO-PAY-REFUND','CNY',19900,'SUCCEEDED',UTC_TIMESTAMP(6));

INSERT INTO mb_refund
    (id, tenant_id, account_id, payment_id, idempotency_key, request_hash, external_reference,
     currency, amount_minor, status, completed_at)
VALUES
    (900001,900001,900001,900004,'demo-refund-success',REPEAT('5',64),'DEMO-REFUND-SUCCESS','CNY',1000,'SUCCEEDED',UTC_TIMESTAMP(6));

INSERT INTO mb_channel_transaction
    (id, tenant_id, payment_id, refund_id, channel_code, external_reference, transaction_type,
     currency, amount_minor, status)
VALUES
    (900001,900001,900001,NULL,'SIMULATED','DEMO-PAY-SUCCESS','CHARGE','CNY',13400,'SUCCEEDED'),
    (900002,900001,900002,NULL,'SIMULATED','DEMO-PAY-UNKNOWN','CHARGE','CNY',9900,'UNKNOWN'),
    (900003,900001,900003,NULL,'SIMULATED','DEMO-PAY-REVOKED','CHARGE','CNY',9900,'REVOKED'),
    (900004,900001,900004,NULL,'SIMULATED','DEMO-PAY-REFUND','CHARGE','CNY',19900,'SUCCEEDED'),
    (900005,900001,NULL,900001,'SIMULATED','DEMO-REFUND-SUCCESS','REFUND','CNY',1000,'SUCCEEDED');

INSERT INTO mb_payment_transaction
    (id, tenant_id, account_id, payment_id, refund_id, sequence_no, transaction_type, status,
     external_reference, currency, amount_minor, event_key, effective_at)
VALUES
    (900001,900001,900001,900001,NULL,1,'PURCHASE','SUCCEEDED','DEMO-PAY-SUCCESS','CNY',13400,'demo:payment:success',UTC_TIMESTAMP(6)),
    (900002,900001,900001,900002,NULL,1,'PURCHASE','UNKNOWN','DEMO-PAY-UNKNOWN','CNY',9900,'demo:payment:unknown',UTC_TIMESTAMP(6)),
    (900003,900001,900001,900003,NULL,1,'PURCHASE','SUCCEEDED','DEMO-PAY-REVOKED','CNY',9900,'demo:payment:before-revoke',UTC_TIMESTAMP(6)),
    (900004,900001,900001,900003,NULL,2,'REVOCATION','REVOKED','DEMO-PAY-REVOKED','CNY',9900,'demo:payment:revoked',UTC_TIMESTAMP(6)),
    (900005,900001,900001,900004,NULL,1,'PURCHASE','SUCCEEDED','DEMO-PAY-REFUND','CNY',19900,'demo:payment:refund-source',UTC_TIMESTAMP(6)),
    (900006,900001,900001,NULL,900001,1,'REFUND','SUCCEEDED','DEMO-REFUND-SUCCESS','CNY',1000,'demo:refund:success',UTC_TIMESTAMP(6));

INSERT INTO mb_payment_revocation
    (id, tenant_id, payment_id, original_transaction_id, revocation_transaction_id, reason, actor_id)
VALUES (900001,900001,900003,900011,900012,'固定演示：撤销错误收款',900001);

INSERT INTO mb_reconciliation_run
    (id, tenant_id, idempotency_key, request_hash, status, started_at, completed_at)
VALUES (900001,900001,'demo-reconciliation',REPEAT('f',64),'COMPLETED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
