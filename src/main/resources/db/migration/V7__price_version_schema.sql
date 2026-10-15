
-- M02-03 Price and Price Version

CREATE TABLE mb_price (
                          id BIGINT NOT NULL AUTO_INCREMENT,
                          plan_id BIGINT NOT NULL,
                          currency CHAR(3) NOT NULL,
                          billing_period VARCHAR(16) NOT NULL,
                          created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                          PRIMARY KEY (id),
                          UNIQUE KEY uk_price_dimension
                              (plan_id, currency, billing_period),

                          CONSTRAINT fk_price_plan
                              FOREIGN KEY (plan_id)
                                  REFERENCES mb_plan(id)
                                  ON DELETE RESTRICT,

                          CONSTRAINT chk_price_period
                              CHECK (billing_period IN ('MONTHLY', 'YEARLY'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE mb_price_version (
                                  id BIGINT NOT NULL AUTO_INCREMENT,
                                  price_id BIGINT NOT NULL,
                                  version_no INT NOT NULL,
                                  base_amount_minor BIGINT NOT NULL,
                                  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
                                  effective_at DATETIME(6) NULL,
                                  revision INT NOT NULL DEFAULT 0,

                                  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                      ON UPDATE CURRENT_TIMESTAMP(6),

                                  PRIMARY KEY (id),

                                  UNIQUE KEY uk_price_version_no
                                      (price_id, version_no),

                                  UNIQUE KEY uk_price_effective_time
                                      (price_id, effective_at),

                                  KEY idx_price_version_effective
                                      (price_id, status, effective_at),

                                  CONSTRAINT fk_price_version_price
                                      FOREIGN KEY (price_id)
                                          REFERENCES mb_price(id)
                                          ON DELETE RESTRICT,

                                  CONSTRAINT chk_price_amount
                                      CHECK (base_amount_minor >= 0),

                                  CONSTRAINT chk_price_version_no
                                      CHECK (version_no >= 1),

                                  CONSTRAINT chk_price_revision
                                      CHECK (revision >= 0),

                                  CONSTRAINT chk_price_version_state
                                      CHECK (
                                          (status = 'DRAFT' AND effective_at IS NULL)
                                              OR
                                          (status = 'PUBLISHED' AND effective_at IS NOT NULL)
                                          )
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;
