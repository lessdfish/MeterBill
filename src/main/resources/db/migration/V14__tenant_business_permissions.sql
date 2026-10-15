INSERT IGNORE INTO mb_role_permission (role_id, permission_code)
SELECT r.id, p.permission_code
FROM mb_role r
CROSS JOIN (
    SELECT 'SUBSCRIPTION_MANAGE' AS permission_code
    UNION ALL SELECT 'PAYMENT_MANAGE'
    UNION ALL SELECT 'RECONCILIATION_RUN'
) p
WHERE r.role_code IN ('OWNER', 'ADMIN');
