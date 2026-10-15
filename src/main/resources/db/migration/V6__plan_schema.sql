CREATE TABLE mb_plan (
                         id BIGINT NOT NULL AUTO_INCREMENT,
                         product_id BIGINT NOT NULL,
                         plan_code VARCHAR(64) NOT NULL,
                         name VARCHAR(128) NOT NULL,
                         description VARCHAR(512) NULL,
                         status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
                         version INT NOT NULL DEFAULT 0,

                         created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                         updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                             ON UPDATE CURRENT_TIMESTAMP(6),

                         PRIMARY KEY (id),

                         UNIQUE KEY uk_plan_product_code (product_id, plan_code),
                         KEY idx_plan_product_id (product_id, id),
                         KEY idx_plan_product_status (product_id, status, id),

                         CONSTRAINT fk_plan_product
                             FOREIGN KEY (product_id)
                                 REFERENCES mb_product(id)
                                 ON DELETE RESTRICT,

                         CONSTRAINT chk_plan_status
                             CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),

                         CONSTRAINT chk_plan_version
                             CHECK (version >= 0)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
    COMMENT='Platform product plan';