UPDATE mb_product
SET name = CASE
    WHEN name = 'AI API Service' THEN 'AI 接口服务'
    WHEN name = 'Acceptance product' THEN '验收产品'
    ELSE name
END
WHERE name IN ('AI API Service', 'Acceptance product');

UPDATE mb_plan
SET name = CASE
    WHEN name = 'Acceptance plan' THEN '验收套餐'
    WHEN name = 'Acceptance next plan' THEN '验收升级套餐'
    ELSE name
END
WHERE name IN ('Acceptance plan', 'Acceptance next plan');
