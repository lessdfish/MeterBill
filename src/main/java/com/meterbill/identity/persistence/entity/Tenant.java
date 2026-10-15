package com.meterbill.identity.persistence.entity;

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
 * SaaS tenant
 * </p>
 *
 * @author MeterBill
 * @since 2026-10-10
 */
@Getter
@Setter
@ToString
@TableName("mb_tenant")
public class Tenant implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_code")
    private String tenantCode;

    @TableField("name")
    private String name;

    @TableField("billing_zone_id")
    private String billingZoneId;

    @TableField("status")
    private String status;

    @TableField("version")
    private Integer version;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
