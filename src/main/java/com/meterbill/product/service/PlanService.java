
package com.meterbill.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meterbill.common.api.PageResponse;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.product.domain.PlanStatus;
import com.meterbill.product.domain.ProductStatus;
import com.meterbill.product.persistence.entity.Plan;
import com.meterbill.product.persistence.entity.Product;
import com.meterbill.product.persistence.mapper.PlanMapper;
import com.meterbill.product.persistence.mapper.ProductMapper;
import com.meterbill.product.security.PlatAdminGuard;
import com.meterbill.product.web.dto.CreatePlanRequest;
import com.meterbill.product.web.dto.UpdatePlanRequest;
import com.meterbill.product.web.vo.PlanVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final ProductMapper productMapper;
    private final PlanMapper planMapper;
    private final PlatAdminGuard adminGuard;
    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    @Transactional
    public Long create(
            Long userId,
            Long productId,
            CreatePlanRequest request) {

        adminGuard.requireAdmin(userId);

        Product product = requireProduct(productId);

        // 仅允许为草稿或已发布产品新增草稿套餐
        if (!ProductStatus.DRAFT.name().equals(product.getStatus())
                && !ProductStatus.ACTIVE.name().equals(product.getStatus())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "当前产品状态不允许新增套餐"
            );
        }

        Plan plan = new Plan();
        plan.setProductId(productId);
        plan.setPlanCode(request.planCode());
        plan.setName(request.name().trim());
        plan.setDescription(request.description());
        plan.setStatus(PlanStatus.DRAFT.name());
        plan.setVersion(0);

        try {
            planMapper.insert(plan);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "当前产品下套餐编码已存在"
            );
        }

        return plan.getId();
    }

    @Transactional(readOnly = true)
    public PageResponse<PlanVO> list(
            Long userId,
            Long productId,
            long page,
            long size) {

        adminGuard.requireAdmin(userId);
        requireProduct(productId);

        if (page < 1 || size < 1 || size > 200) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT,
                    "分页参数不合法"
            );
        }

        Page<Plan> result = planMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Plan>()
                        .eq(Plan::getProductId, productId)
                        .orderByDesc(Plan::getId)
        );

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getRecords().stream()
                        .map(PlanVO::from)
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public PlanVO detail(
            Long userId,
            Long productId,
            Long planId) {

        adminGuard.requireAdmin(userId);
        return PlanVO.from(requirePlan(productId, planId));
    }

    @Transactional
    public PlanVO update(
            Long userId,
            Long productId,
            Long planId,
            UpdatePlanRequest request) {

        adminGuard.requireAdmin(userId);

        Plan plan = requirePlan(productId, planId);

        if (!PlanStatus.DRAFT.name().equals(plan.getStatus())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "只允许修改草稿套餐"
            );
        }

        // 同时校验产品、状态及版本，防止并发覆盖
        int changed = planMapper.update(
                null,
                new LambdaUpdateWrapper<Plan>()
                        .eq(Plan::getId, planId)
                        .eq(Plan::getProductId, productId)
                        .eq(Plan::getStatus, PlanStatus.DRAFT.name())
                        .eq(Plan::getVersion, request.version())
                        .set(Plan::getName, request.name().trim())
                        .set(Plan::getDescription, request.description())
                        .setSql("version = version + 1")
        );

        if (changed != 1) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "套餐已被其他请求修改，请刷新后重试"
            );
        }

        return PlanVO.from(requirePlan(productId, planId));
    }

    @Transactional
    public PlanVO publish(Long userId, Long productId, Long planId) {
        adminGuard.requireAdmin(userId);
        requireProduct(productId);
        requirePlan(productId, planId);

        String productStatus = jdbc.queryForObject("""
                SELECT status FROM mb_product WHERE id = ? FOR UPDATE
                """, String.class, productId);
        if (!ProductStatus.DRAFT.name().equals(productStatus)
                && !ProductStatus.ACTIVE.name().equals(productStatus)) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "当前产品状态不允许发布套餐");
        }

        jdbc.queryForObject("""
                SELECT id FROM mb_plan
                WHERE id = ? AND product_id = ?
                FOR UPDATE
                """, Long.class, planId, productId);
        Plan plan = requirePlan(productId, planId);
        if (!PlanStatus.DRAFT.name().equals(plan.getStatus())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "只有草稿套餐可以发布");
        }

        Long currentMonthlyPrices = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mb_price p
                JOIN mb_price_version pv ON pv.price_id = p.id
                WHERE p.plan_id = ?
                  AND p.billing_period = 'MONTHLY'
                  AND pv.status = 'PUBLISHED'
                  AND pv.effective_at <= UTC_TIMESTAMP(6)
                """, Long.class, planId);
        if (currentMonthlyPrices == null || currentMonthlyPrices == 0) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "套餐至少需要一个当前已生效的月价版本");
        }

        int changed = jdbc.update("""
                UPDATE mb_plan
                SET status = 'ACTIVE', version = version + 1
                WHERE id = ? AND product_id = ? AND status = 'DRAFT'
                """, planId, productId);
        if (changed != 1) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "套餐状态已变化，请刷新后重试");
        }

        audit.record(null, userId, "PLAN_PUBLISH", "PLAN", planId.toString());
        return PlanVO.from(requirePlan(productId, planId));
    }

    @Transactional
    public PlanVO retire(Long userId, Long productId, Long planId) {
        adminGuard.requireAdmin(userId);
        requireProduct(productId);
        requirePlan(productId, planId);

        jdbc.queryForObject("""
                SELECT id FROM mb_plan
                WHERE id = ? AND product_id = ?
                FOR UPDATE
                """, Long.class, planId, productId);

        Plan plan = requirePlan(productId, planId);
        if (PlanStatus.RETIRED.name().equals(plan.getStatus())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "套餐已停用，无需重复操作");
        }

        int changed = jdbc.update("""
                UPDATE mb_plan
                SET status = 'RETIRED', version = version + 1
                WHERE id = ? AND product_id = ? AND status IN ('DRAFT', 'ACTIVE')
                """, planId, productId);
        if (changed != 1) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "套餐状态已变化，请刷新后重试");
        }

        audit.record(null, userId, "PLAN_RETIRE", "PLAN", planId.toString());
        return PlanVO.from(requirePlan(productId, planId));
    }

    private Product requireProduct(Long productId) {
        Product product = productMapper.selectById(productId);

        if (product == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "产品不存在"
            );
        }

        return product;
    }

    private Plan requirePlan(Long productId, Long planId) {
        Plan plan = planMapper.selectOne(
                new LambdaQueryWrapper<Plan>()
                        .eq(Plan::getId, planId)
                        .eq(Plan::getProductId, productId)
        );

        if (plan == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "套餐不存在"
            );
        }

        return plan;
    }
}
