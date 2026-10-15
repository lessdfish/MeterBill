
package com.meterbill.product.web.vo;

import com.meterbill.product.persistence.entity.Product;

import java.time.LocalDateTime;

public record ProductVO(
        Long id,
        String productCode,
        String name,
        String description,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProductVO from(Product product) {
        return new ProductVO(
                product.getId(),
                product.getProductCode(),
                product.getName(),
                product.getDescription(),
                product.getStatus(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
