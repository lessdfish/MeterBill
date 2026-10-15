package com.meterbill.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meterbill.common.api.PageResponse;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.product.domain.ProductStatus;
import com.meterbill.product.persistence.entity.Product;
import com.meterbill.product.persistence.mapper.ProductMapper;
import com.meterbill.product.security.PlatAdminGuard;
import com.meterbill.product.service.ProductService;
import com.meterbill.product.web.dto.CreateProduct;
import com.meterbill.product.web.vo.ProductVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ClassName:ProductServiceImpl
 * Package:com.meterbill.product.service.impl
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/11 - 13:32
 * @Version: v1.0
 *
 */
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {
    private final ProductMapper productMapper;
    private final PlatAdminGuard platformAdminGuard;
    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    @Override
    @Transactional
    public Long create(Long userId, CreateProduct request) {
        platformAdminGuard.requireAdmin(userId);

        Product product = new Product();
        product.setProductCode(request.productCode());
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setStatus(ProductStatus.DRAFT.name());

        try {
            productMapper.insert(product);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "产品编码已存在"
            );
        }

        return product.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductVO> list(
            Long userId, long page, long size) {

        platformAdminGuard.requireAdmin(userId);

        if (page < 1 || size < 1 || size > 200) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT,
                    "分页参数不合法"
            );
        }

        Page<Product> result = productMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Product>()
                        .orderByDesc(Product::getId)
        );

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getRecords().stream()
                        .map(ProductVO::from)
                        .toList()
        );
    }

    @Override
    @Transactional
    public ProductVO publish(Long userId, Long productId) {
        platformAdminGuard.requireAdmin(userId);
        Product existing = productMapper.selectById(productId);
        if (existing == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "产品不存在");
        }

        Product product = jdbc.queryForObject("""
                SELECT id, product_code, name, description, status,
                       created_at, updated_at
                FROM mb_product
                WHERE id = ?
                FOR UPDATE
                """, (rs, rowNum) -> {
            Product value = new Product();
            value.setId(rs.getLong("id"));
            value.setProductCode(rs.getString("product_code"));
            value.setName(rs.getString("name"));
            value.setDescription(rs.getString("description"));
            value.setStatus(rs.getString("status"));
            value.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            value.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            return value;
        }, productId);

        if (!ProductStatus.DRAFT.name().equals(product.getStatus())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "只有草稿产品可以发布");
        }

        Long activePlans = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mb_plan
                WHERE product_id = ? AND status = 'ACTIVE'
                """, Long.class, productId);

        if (activePlans == null || activePlans == 0) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "产品至少需要一个已发布且具有生效月价的套餐");
        }

        int changed = jdbc.update("""
                UPDATE mb_product
                SET status = 'ACTIVE'
                WHERE id = ? AND status = 'DRAFT'
                """, productId);

        if (changed != 1) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "产品状态已变化，请刷新后重试");
        }

        audit.record(null, userId, "PRODUCT_PUBLISH", "PRODUCT", productId.toString());
        product.setStatus(ProductStatus.ACTIVE.name());
        return ProductVO.from(product);
    }
}
