CREATE TABLE mb_product
(
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    product_code VARCHAR(64) NOT NULL,

    name VARCHAR(128) NOT NULL,

    description VARCHAR(512),

    status VARCHAR(32) NOT NULL,

    created_at DATETIME NOT NULL,

    updated_at DATETIME NOT NULL,

    UNIQUE KEY uk_product_code
        (
         product_code
            ),

    KEY idx_product_status
        (
         status
            )
);