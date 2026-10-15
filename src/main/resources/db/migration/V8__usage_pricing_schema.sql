
-- M02-04: Usage metering definitions and pricing rules

CREATE TABLE mb_usage_meter (
                                id BIGINT NOT NULL AUTO_INCREMENT,
                                product_id BIGINT NOT NULL,
                                meter_code VARCHAR(64) NOT NULL,
                                name VARCHAR(128) NOT NULL,
                                unit VARCHAR(32) NOT NULL,
                                created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

                                PRIMARY KEY (id),
                                UNIQUE KEY uk_meter_product_code (product_id, meter_code),

                                CONSTRAINT fk_usage_meter_product
                                    FOREIGN KEY (product_id) REFERENCES mb_product(id)
                                        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE mb_usage_price_rule (
                                     id BIGINT NOT NULL AUTO_INCREMENT,
                                     price_version_id BIGINT NOT NULL,
                                     meter_id BIGINT NOT NULL,
                                     pricing_mode VARCHAR(20) NOT NULL,
                                     included_quantity BIGINT NOT NULL DEFAULT 0,
                                     unit_amount_minor BIGINT NULL,
                                     created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                                     updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                         ON UPDATE CURRENT_TIMESTAMP(6),

                                     PRIMARY KEY (id),
                                     UNIQUE KEY uk_usage_rule_version_meter
                                         (price_version_id, meter_id),

                                     CONSTRAINT fk_usage_rule_version
                                         FOREIGN KEY (price_version_id)
                                             REFERENCES mb_price_version(id) ON DELETE RESTRICT,

                                     CONSTRAINT fk_usage_rule_meter
                                         FOREIGN KEY (meter_id)
                                             REFERENCES mb_usage_meter(id) ON DELETE RESTRICT,

                                     CONSTRAINT chk_usage_rule_quantity
                                         CHECK (included_quantity >= 0),

                                     CONSTRAINT chk_usage_rule_mode
                                         CHECK (pricing_mode IN (
                                                                 'PER_UNIT', 'GRADUATED', 'VOLUME'
                                             )),

                                     CONSTRAINT chk_usage_rule_amount
                                         CHECK (
                                             (pricing_mode = 'PER_UNIT'
                                                 AND unit_amount_minor IS NOT NULL
                                                 AND unit_amount_minor >= 0)
                                                 OR
                                             (pricing_mode IN ('GRADUATED', 'VOLUME')
                                                 AND unit_amount_minor IS NULL)
                                             )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;


CREATE TABLE mb_usage_price_tier (
                                     id BIGINT NOT NULL AUTO_INCREMENT,
                                     rule_id BIGINT NOT NULL,
                                     tier_no INT NOT NULL,
                                     up_to_quantity BIGINT NULL,
                                     unit_amount_minor BIGINT NOT NULL,

                                     PRIMARY KEY (id),
                                     UNIQUE KEY uk_usage_tier_order (rule_id, tier_no),

                                     CONSTRAINT fk_usage_tier_rule
                                         FOREIGN KEY (rule_id)
                                             REFERENCES mb_usage_price_rule(id) ON DELETE RESTRICT,

                                     CONSTRAINT chk_usage_tier_no CHECK (tier_no > 0),
                                     CONSTRAINT chk_usage_tier_bound
                                         CHECK (up_to_quantity IS NULL OR up_to_quantity > 0),
                                     CONSTRAINT chk_usage_tier_amount
                                         CHECK (unit_amount_minor >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;
