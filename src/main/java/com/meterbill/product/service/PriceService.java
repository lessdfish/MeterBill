
package com.meterbill.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meterbill.common.api.PageResponse;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.product.domain.BillingPeriod;
import com.meterbill.product.domain.PlanStatus;
import com.meterbill.product.domain.PriceVersionStatus;
import com.meterbill.product.domain.ProductStatus;
import com.meterbill.product.persistence.entity.*;
import com.meterbill.product.persistence.mapper.*;
import com.meterbill.product.security.PlatAdminGuard;
import com.meterbill.product.web.dto.*;
import com.meterbill.product.web.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PriceService {

    private final ProductMapper productMapper;
    private final PlanMapper planMapper;
    private final PriceMapper priceMapper;
    private final PriceVersionMapper versionMapper;
    private final PlatAdminGuard adminGuard;
    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    @Transactional
    public Long createPrice(
            Long userId, Long productId, Long planId,
            CreatePriceRequest request) {

        adminGuard.requireAdmin(userId);
        requireWritablePlan(productId, planId);

        try {
            Currency currency = Currency.getInstance(request.currency());

            if (currency.getDefaultFractionDigits() < 0
                    || "XXX".equals(request.currency())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT, "不支持的货币编码");
        }

        Price price = new Price();
        price.setPlanId(planId);
        price.setCurrency(request.currency());
        price.setBillingPeriod(
                BillingPeriod.valueOf(request.billingPeriod()).name());

        try {
            priceMapper.insert(price);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "当前套餐已存在相同币种和计费周期的价格");
        }

        return price.getId();
    }

    @Transactional(readOnly = true)
    public PageResponse<PriceVO> listPrices(
            Long userId, Long productId, Long planId,
            long page, long size) {

        adminGuard.requireAdmin(userId);
        requirePlan(productId, planId);
        validatePage(page, size);

        Page<Price> result = priceMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Price>()
                        .eq(Price::getPlanId, planId)
                        .orderByDesc(Price::getId));

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getRecords().stream()
                        .map(PriceVO::from).toList());
    }

    @Transactional
    public Long createVersion(
            Long userId, Long productId, Long planId,
            Long priceId, CreatePriceVersionRequest request) {

        adminGuard.requireAdmin(userId);
        requireWritablePlan(productId, planId);
        requirePrice(planId, priceId);

        // Serialize version allocation per price.
        lockPrice(priceId);

        List<Long> drafts = jdbc.queryForList("""
        SELECT id
        FROM mb_price_version
        WHERE price_id = ? AND status = 'DRAFT'
        FOR UPDATE
        """, Long.class, priceId);

        if (!drafts.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "该价格已有未发布的草稿版本");
        }

        List<Integer> numbers = jdbc.queryForList("""
        SELECT version_no
        FROM mb_price_version
        WHERE price_id = ?
        ORDER BY version_no DESC
        LIMIT 1
        FOR UPDATE
        """, Integer.class, priceId);

        int nextVersion = numbers.isEmpty()
                ? 1 : numbers.getFirst() + 1;
        // 并发测试 的代码

        PriceVersion version = new PriceVersion();
        version.setPriceId(priceId);
        version.setVersionNo(nextVersion);
        version.setBaseAmountMinor(request.baseAmountMinor());
        version.setStatus(PriceVersionStatus.DRAFT.name());
        version.setRevision(0);

        versionMapper.insert(version);

        audit.record(null,userId,"PRICE_VERSION_CREATE","PRICE_VERSION",version.getId().toString());
        return version.getId();
    }

    @Transactional(readOnly = true)
    public PageResponse<PriceVersionVO> listVersions(
            Long userId, Long productId, Long planId, Long priceId,
            long page, long size) {

        adminGuard.requireAdmin(userId);
        requirePlan(productId, planId);
        requirePrice(planId, priceId);
        validatePage(page, size);

        Page<PriceVersion> result = versionMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<PriceVersion>()
                        .eq(PriceVersion::getPriceId, priceId)
                        .orderByDesc(PriceVersion::getVersionNo));

        return new PageResponse<>(
                result.getCurrent(),
                result.getSize(),
                result.getTotal(),
                result.getRecords().stream()
                        .map(PriceVersionVO::from).toList());
    }

    @Transactional
    public PriceVersionVO updateDraft(
            Long userId, Long productId, Long planId,
            Long priceId, Long versionId,
            UpdatePriceVersionRequest request) {

        adminGuard.requireAdmin(userId);
        requireWritablePlan(productId, planId);
        requirePrice(planId, priceId);

        PriceVersion version = requireVersion(priceId, versionId);

        if (!PriceVersionStatus.DRAFT.name()
                .equals(version.getStatus())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "已发布价格版本不允许修改");
        }

        int changed = versionMapper.update(
                null,
                new LambdaUpdateWrapper<PriceVersion>()
                        .eq(PriceVersion::getId, versionId)
                        .eq(PriceVersion::getPriceId, priceId)
                        .eq(PriceVersion::getStatus, "DRAFT")
                        .eq(PriceVersion::getRevision, request.revision())
                        .set(PriceVersion::getBaseAmountMinor,
                                request.baseAmountMinor())
                        .setSql("revision = revision + 1"));

        audit.record(
                null, userId, "PRICE_VERSION_DRAFT_UPDATE",
                "PRICE_VERSION", versionId.toString());

        if (changed != 1) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "价格草稿已变更，请刷新后重试");
        }


        return PriceVersionVO.from(
                requireVersion(priceId, versionId));
    }

    @Transactional
    public PriceVersionVO publish(
            Long userId, Long productId, Long planId,
            Long priceId, Long versionId,
            PublishPriceVersionRequest request) {

        adminGuard.requireAdmin(userId);
        requireWritablePlan(productId, planId);
        requirePrice(planId, priceId);

        // All create/publish operations lock the same price row.
        lockPrice(priceId);

        PriceVersion current = requireVersion(priceId, versionId);

        if (!PriceVersionStatus.DRAFT.name()
                .equals(current.getStatus())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "该价格版本已经发布");
        }

        if (!current.getRevision().equals(request.revision())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "草稿版本已变化，请刷新后重新确认发布");
        }

        Instant effectiveAt = request.effectiveAt();

        if (effectiveAt.isBefore(Instant.now())) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT,
                    "不允许将新价格版本回溯发布到过去");
        }

        LocalDateTime utcTime = LocalDateTime.ofInstant(
                effectiveAt, ZoneOffset.UTC);


        List<PriceVersion> latest = jdbc.query("""
        SELECT version_no, effective_at
        FROM mb_price_version
        WHERE price_id = ? AND status = 'PUBLISHED'
        ORDER BY version_no DESC
        LIMIT 1
        FOR UPDATE
        """,
                (rs, row) -> {
                    PriceVersion value = new PriceVersion();
                    value.setVersionNo(rs.getInt("version_no"));
                    value.setEffectiveAt(
                            rs.getObject("effective_at", LocalDateTime.class));
                    return value;
                },
                priceId);

        // 当前读

        if (!latest.isEmpty()) {
            PriceVersion previous = latest.getFirst();

            if (current.getVersionNo() <= previous.getVersionNo()
                    || !utcTime.isAfter(previous.getEffectiveAt())) {
                throw new BusinessException(
                        ErrorCode.BUSINESS_CONFLICT,
                        "价格版本号和生效时间必须严格递增");
            }
        }

        int changed = versionMapper.update(
                null,
                new LambdaUpdateWrapper<PriceVersion>()
                        .eq(PriceVersion::getId, versionId)
                        .eq(PriceVersion::getPriceId, priceId)
                        .eq(PriceVersion::getStatus, "DRAFT")
                        .eq(PriceVersion::getRevision, request.revision())
                        .set(PriceVersion::getStatus, "PUBLISHED")
                        .set(PriceVersion::getEffectiveAt, utcTime)
                        .setSql("revision = revision + 1"));

        audit.record(
                null, userId, "PRICE_VERSION_PUBLISH",
                "PRICE_VERSION", versionId.toString());

        if (changed != 1) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "价格发布冲突，请刷新后重试");
        }

        return PriceVersionVO.from(
                requireVersion(priceId, versionId));
    }

    @Transactional(readOnly = true)
    public PriceVersionVO effectiveVersion(
            Long userId, Long productId, Long planId,
            Long priceId, Instant at) {

        adminGuard.requireAdmin(userId);
        requirePlan(productId, planId);
        requirePrice(planId, priceId);

        Instant queryTime = at == null ? Instant.now() : at;
        LocalDateTime utcTime = LocalDateTime.ofInstant(
                queryTime, ZoneOffset.UTC);

        List<PriceVersion> result = versionMapper.selectList(
                new LambdaQueryWrapper<PriceVersion>()
                        .eq(PriceVersion::getPriceId, priceId)
                        .eq(PriceVersion::getStatus, "PUBLISHED")
                        .le(PriceVersion::getEffectiveAt, utcTime)
                        .orderByDesc(PriceVersion::getEffectiveAt)
                        .last("LIMIT 1"));

        if (result.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "指定时间不存在已生效的价格版本");
        }

        return PriceVersionVO.from(result.getFirst());
    }

    private void lockPrice(Long priceId) {
        jdbc.queryForObject("""
                SELECT id
                FROM mb_price
                WHERE id = ?
                FOR UPDATE
                """, Long.class, priceId);
    }

    private Plan requirePlan(Long productId, Long planId) {
        Plan plan = planMapper.selectById(planId);

        if (plan == null
                || !productId.equals(plan.getProductId())) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "套餐不存在");
        }

        return plan;
    }

    private void requireWritablePlan(Long productId, Long planId) {
        Product product = productMapper.selectById(productId);
        Plan plan = requirePlan(productId, planId);

        if (product == null
                || !(ProductStatus.DRAFT.name().equals(product.getStatus())
                || ProductStatus.ACTIVE.name().equals(product.getStatus()))
                || !(PlanStatus.DRAFT.name().equals(plan.getStatus())
                || PlanStatus.ACTIVE.name().equals(plan.getStatus()))) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "当前产品或套餐状态不允许修改价格");
        }
    }

    private Price requirePrice(Long planId, Long priceId) {
        Price price = priceMapper.selectById(priceId);

        if (price == null || !planId.equals(price.getPlanId())) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "价格不存在");
        }

        return price;
    }

    private PriceVersion requireVersion(Long priceId, Long versionId) {
        PriceVersion value = versionMapper.selectById(versionId);

        if (value == null || !priceId.equals(value.getPriceId())) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND, "价格版本不存在");
        }

        return value;
    }

    private void validatePage(long page, long size) {
        if (page < 1 || size < 1 || size > 200) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT, "分页参数不合法");
        }
    }
}
