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
@TableName("mb_price")
public class Price implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("plan_id")
    private Long planId;

    @TableField("currency")
    private String currency;

    @TableField("billing_period")
    private String billingPeriod;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
