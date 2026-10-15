
-- M02-01: Product foundation hardening

ALTER TABLE mb_product
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6),
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    ADD CONSTRAINT chk_product_status
        CHECK (status IN (
                          'DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED'
            ));

-- Platform-level authorization, independent of tenant roles.
CREATE TABLE mb_platform_admin (
                                   user_id BIGINT NOT NULL,
                                   granted_at DATETIME(6) NOT NULL
                                       DEFAULT CURRENT_TIMESTAMP(6),

                                   PRIMARY KEY (user_id),

                                   CONSTRAINT fk_platform_admin_user
                                       FOREIGN KEY (user_id) REFERENCES mb_user(id)
                                           ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
