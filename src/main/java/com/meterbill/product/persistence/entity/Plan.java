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
 * Platform product plan
 * </p>
 *
 * @author MeterBill
 * @since 2026-10-11
 */
@Getter
@Setter
@ToString
@TableName("mb_plan")
public class Plan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("product_id")
    private Long productId;

    @TableField("plan_code")
    private String planCode;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("status")
    private String status;

    @TableField("version")
    private Integer version;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
