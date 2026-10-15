
package com.meterbill.product.web.vo;

import com.meterbill.product.persistence.entity.Plan;

import java.time.LocalDateTime;

public record PlanVO(
        Long id,
        Long productId,
        String planCode,
        String name,
        String description,
        String status,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PlanVO from(Plan plan) {
        return new PlanVO(
                plan.getId(),
                plan.getProductId(),
                plan.getPlanCode(),
                plan.getName(),
                plan.getDescription(),
                plan.getStatus(),
                plan.getVersion(),
                plan.getCreatedAt(),
                plan.getUpdatedAt()
        );
    }
}
