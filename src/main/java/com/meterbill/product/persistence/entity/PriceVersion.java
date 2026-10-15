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
@TableName("mb_price_version")
public class PriceVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("price_id")
    private Long priceId;

    @TableField("version_no")
    private Integer versionNo;

    @TableField("base_amount_minor")
    private Long baseAmountMinor;

    @TableField("status")
    private String status;

    @TableField("effective_at")
    private LocalDateTime effectiveAt;

    @TableField("revision")
    private Integer revision;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
