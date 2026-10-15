
-- M01-01: Tenant and identity foundation

CREATE TABLE mb_tenant (
                           id BIGINT NOT NULL AUTO_INCREMENT,
                           tenant_code VARCHAR(64) NOT NULL,
                           name VARCHAR(128) NOT NULL,
                           billing_zone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
                           status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                           version INT NOT NULL DEFAULT 0,
                           created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                           updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                               ON UPDATE CURRENT_TIMESTAMP(6),

                           PRIMARY KEY (id),
                           UNIQUE KEY uk_tenant_code (tenant_code),
                           KEY idx_tenant_status_id (status, id),

                           CONSTRAINT chk_tenant_status
                               CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
    COMMENT='SaaS tenant';


CREATE TABLE mb_user (
                         id BIGINT NOT NULL AUTO_INCREMENT,
                         email VARCHAR(190) NOT NULL,
                         password_hash VARCHAR(255) NOT NULL,
                         display_name VARCHAR(100) NOT NULL,
                         status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                         version INT NOT NULL DEFAULT 0,
                         created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                         updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                             ON UPDATE CURRENT_TIMESTAMP(6),

                         PRIMARY KEY (id),
                         UNIQUE KEY uk_user_email (email),

                         CONSTRAINT chk_user_status
                             CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
    COMMENT='Global user account';


CREATE TABLE mb_tenant_member (
                                  id BIGINT NOT NULL AUTO_INCREMENT,
                                  tenant_id BIGINT NOT NULL,
                                  user_id BIGINT NOT NULL,
                                  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                                  version INT NOT NULL DEFAULT 0,
                                  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),

                                  PRIMARY KEY (id),
                                  UNIQUE KEY uk_member_tenant_user (tenant_id, user_id),
                                  KEY idx_member_tenant_status (tenant_id, status, id),
                                  KEY idx_member_user_status (user_id, status, tenant_id),

                                  CONSTRAINT fk_member_tenant
                                      FOREIGN KEY (tenant_id) REFERENCES mb_tenant(id)
                                          ON DELETE RESTRICT,

                                  CONSTRAINT fk_member_user
                                      FOREIGN KEY (user_id) REFERENCES mb_user(id)
                                          ON DELETE RESTRICT,

                                  CONSTRAINT chk_member_status
                                      CHECK (status IN ('INVITED', 'ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
    COMMENT='User membership within tenant';
