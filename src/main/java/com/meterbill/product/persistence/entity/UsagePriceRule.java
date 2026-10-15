package com.meterbill.product.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 
 * </p>
 *
 * @author MeterBill
 * @since 2026-10-11
 */
@Getter
@Setter
@ToString
@TableName("mb_usage_price_rule")
public class UsagePriceRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("price_version_id")
    private Long priceVersionId;

    @TableField("meter_id")
    private Long meterId;

    @TableField("pricing_mode")
    private String pricingMode;

    @TableField("included_quantity")
    private Long includedQuantity;

    @TableField("unit_amount_minor")
    private Long unitAmountMinor;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
