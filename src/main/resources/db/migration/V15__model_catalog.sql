CREATE TABLE mb_ai_model (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    model_code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    UNIQUE KEY uk_model_product_code (product_id, model_code),
    CONSTRAINT fk_model_product FOREIGN KEY (product_id) REFERENCES mb_product(id)
);
CREATE TABLE mb_model_meter (
    model_id BIGINT NOT NULL,
    meter_id BIGINT NOT NULL,
    PRIMARY KEY (model_id, meter_id),
    CONSTRAINT fk_model_meter_model FOREIGN KEY (model_id) REFERENCES mb_ai_model(id),
    CONSTRAINT fk_model_meter_meter FOREIGN KEY (meter_id) REFERENCES mb_usage_meter(id)
);
-- Preserve the model/meter relationships actually used by historical accepted facts.
INSERT INTO mb_ai_model (product_id, model_code, name)
SELECT DISTINCT m.product_id, e.model_code, e.model_code
FROM mb_usage_event e JOIN mb_usage_meter m ON m.id=e.meter_id;
INSERT INTO mb_model_meter (model_id, meter_id)
SELECT DISTINCT a.id, e.meter_id FROM mb_usage_event e
JOIN mb_usage_meter m ON m.id=e.meter_id
JOIN mb_ai_model a ON a.product_id=m.product_id AND a.model_code=e.model_code;
