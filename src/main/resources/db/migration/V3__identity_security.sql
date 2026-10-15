
-- M01-03: RBAC, API keys, JWT revocation and security audit

-- Member IDs must be referencable together with tenant IDs.
ALTER TABLE mb_tenant_member
    ADD UNIQUE KEY uk_member_tenant_id (tenant_id, id);

CREATE TABLE mb_role (
                         id BIGINT NOT NULL AUTO_INCREMENT,
                         tenant_id BIGINT NOT NULL,
                         role_code VARCHAR(32) NOT NULL,
                         name VARCHAR(64) NOT NULL,
                         created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                         PRIMARY KEY (id),
                         UNIQUE KEY uk_role_tenant_code (tenant_id, role_code),
                         UNIQUE KEY uk_role_tenant_id (tenant_id, id),

                         CONSTRAINT fk_role_tenant FOREIGN KEY (tenant_id)
                             REFERENCES mb_tenant(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mb_role_permission (
                                    role_id BIGINT NOT NULL,
                                    permission_code VARCHAR(64) NOT NULL,

                                    PRIMARY KEY (role_id, permission_code),

                                    CONSTRAINT fk_permission_role FOREIGN KEY (role_id)
                                        REFERENCES mb_role(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mb_member_role (
                                tenant_id BIGINT NOT NULL,
                                member_id BIGINT NOT NULL,
                                role_id BIGINT NOT NULL,

                                PRIMARY KEY (tenant_id, member_id, role_id),
                                KEY idx_member_role_tenant_role (tenant_id, role_id),

                                CONSTRAINT fk_member_role_member
                                    FOREIGN KEY (tenant_id, member_id)
                                        REFERENCES mb_tenant_member(tenant_id, id)
                                        ON DELETE RESTRICT,

                                CONSTRAINT fk_member_role_role
                                    FOREIGN KEY (tenant_id, role_id)
                                        REFERENCES mb_role(tenant_id, id)
                                        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mb_api_key (
                            id BIGINT NOT NULL AUTO_INCREMENT,
                            tenant_id BIGINT NOT NULL,
                            created_by_member_id BIGINT NOT NULL,
                            name VARCHAR(100) NOT NULL,
                            key_prefix VARCHAR(16) NOT NULL,
                            key_hash BINARY(32) NOT NULL,
                            status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                            expires_at DATETIME(6) NOT NULL,
                            created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                            revoked_at DATETIME(6) NULL,

                            PRIMARY KEY (id),
                            UNIQUE KEY uk_api_key_prefix (key_prefix),
                            KEY idx_api_key_tenant_status (tenant_id, status, id),
                            KEY idx_api_key_expires_at (expires_at),

                            CONSTRAINT fk_api_key_member
                                FOREIGN KEY (tenant_id, created_by_member_id)
                                    REFERENCES mb_tenant_member(tenant_id, id)
                                    ON DELETE RESTRICT,

                            CONSTRAINT chk_api_key_status
                                CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mb_revoked_jwt (
                                jti VARCHAR(64) NOT NULL,
                                user_id BIGINT NOT NULL,
                                expires_at DATETIME(6) NOT NULL,
                                revoked_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                PRIMARY KEY (jti),
                                KEY idx_revoked_jwt_expires (expires_at),

                                CONSTRAINT fk_revoked_jwt_user FOREIGN KEY (user_id)
                                    REFERENCES mb_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mb_security_audit (
                                   id BIGINT NOT NULL AUTO_INCREMENT,
                                   tenant_id BIGINT NULL,
                                   actor_type VARCHAR(20) NOT NULL,
                                   actor_id BIGINT NULL,
                                   action VARCHAR(64) NOT NULL,
                                   target_type VARCHAR(32) NULL,
                                   target_id VARCHAR(64) NULL,
                                   outcome VARCHAR(16) NOT NULL,
                                   request_id VARCHAR(36) NULL,
                                   created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                   PRIMARY KEY (id),
                                   KEY idx_audit_tenant_time (tenant_id, id),
                                   KEY idx_audit_actor (actor_type, actor_id, id),
                                   KEY idx_audit_action_time (action, id),

                                   CONSTRAINT fk_audit_tenant FOREIGN KEY (tenant_id)
                                       REFERENCES mb_tenant(id) ON DELETE RESTRICT,

                                   CONSTRAINT chk_audit_outcome
                                       CHECK (outcome IN ('SUCCESS', 'FAILURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Create standard roles for tenants that already exist.
INSERT INTO mb_role (tenant_id, role_code, name)
SELECT t.id, roles.role_code, roles.role_name
FROM mb_tenant t
         CROSS JOIN (
    SELECT 'OWNER' AS role_code, 'Tenant Owner' AS role_name
    UNION ALL SELECT 'ADMIN', 'Tenant Administrator'
    UNION ALL SELECT 'VIEWER', 'Tenant Viewer'
) roles;

-- Every standard role can read basic tenant information.
INSERT INTO mb_role_permission (role_id, permission_code)
SELECT id, 'TENANT_READ'
FROM mb_role;

-- OWNER and ADMIN have management permissions.
INSERT INTO mb_role_permission (role_id, permission_code)
SELECT r.id, p.permission_code
FROM mb_role r
         CROSS JOIN (
    SELECT 'MEMBER_ROLE_MANAGE' AS permission_code
    UNION ALL SELECT 'API_KEY_MANAGE'
    UNION ALL SELECT 'AUDIT_READ'
) p
WHERE r.role_code IN ('OWNER', 'ADMIN');
