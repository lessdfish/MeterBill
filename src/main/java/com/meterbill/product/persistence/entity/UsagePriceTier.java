package com.meterbill.product.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

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
@TableName("mb_usage_price_tier")
public class UsagePriceTier implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("rule_id")
    private Long ruleId;

    @TableField("tier_no")
    private Integer tierNo;

    @TableField("up_to_quantity")
    private Long upToQuantity;

    @TableField("unit_amount_minor")
    private Long unitAmountMinor;
}
