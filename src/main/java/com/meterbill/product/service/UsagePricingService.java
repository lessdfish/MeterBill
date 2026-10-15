
package com.meterbill.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.product.domain.*;
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

import java.util.List;

@Service
@RequiredArgsConstructor
public class UsagePricingService {

    private final ProductMapper productMapper;
    private final PlanMapper planMapper;
    private final PriceMapper priceMapper;
    private final PriceVersionMapper versionMapper;
    private final UsageMeterMapper meterMapper;
    private final UsagePriceRuleMapper ruleMapper;
    private final UsagePriceTierMapper tierMapper;
    private final PlatAdminGuard adminGuard;
    private final SecurityAuditService audit;
    private final JdbcTemplate jdbc;

    @Transactional
    public Long createMeter(
            Long userId, Long productId,
            CreateUsageMeterRequest request) {

        adminGuard.requireAdmin(userId);

        Product product = requireProduct(productId);
        if (!writableProduct(product.getStatus())) {
            throw conflict("当前产品状态不允许新增计量项");
        }

        UsageMeter meter = new UsageMeter();
        meter.setProductId(productId);
        meter.setMeterCode(request.meterCode());
        meter.setName(request.name().trim());
        meter.setUnit(request.unit());

        try {
            meterMapper.insert(meter);
        } catch (DuplicateKeyException ex) {
            throw conflict("当前产品已存在相同计量项编码");
        }

        audit.record(null, userId, "USAGE_METER_CREATE",
                "USAGE_METER", meter.getId().toString());

        return meter.getId();
    }

    @Transactional(readOnly = true)
    public List<UsageMeterVO> listMeters(
            Long userId, Long productId) {

        adminGuard.requireAdmin(userId);
        requireProduct(productId);

        return meterMapper.selectList(
                new LambdaQueryWrapper<UsageMeter>()
                        .eq(UsageMeter::getProductId, productId)
                        .orderByAsc(UsageMeter::getId)
        ).stream().map(UsageMeterVO::from).toList();
    }

    @Transactional
    public UsageRuleVO upsertRule(
            Long userId,
            Long productId,
            Long planId,
            Long priceId,
            Long versionId,
            Long meterId,
            UpsertUsageRuleRequest request) {

        adminGuard.requireAdmin(userId);
        validateRule(request);

        // 与 M02-03 的版本发布锁使用同一个 mb_price 行。
        lockPrice(priceId, planId);
        requireWritablePlan(productId, planId);

        UsageMeter meter = requireMeter(productId, meterId);
        VersionState state = currentVersion(priceId, versionId);

        if (!"DRAFT".equals(state.status())) {
            throw conflict("已发布价格版本不允许修改用量规则");
        }

        if (!state.revision().equals(request.revision())) {
            throw conflict("价格版本已被修改，请刷新 revision 后重试");
        }

        UsagePriceRule rule = findRule(versionId, meterId);

        if (rule == null) {
            rule = new UsagePriceRule();
            rule.setPriceVersionId(versionId);
            rule.setMeterId(meterId);
        } else {
            tierMapper.delete(
                    new LambdaQueryWrapper<UsagePriceTier>()
                            .eq(UsagePriceTier::getRuleId, rule.getId()));
        }

        rule.setPricingMode(request.mode().name());
        rule.setIncludedQuantity(request.includedQuantity());
        rule.setUnitAmountMinor(request.unitAmountMinor());

        if (rule.getId() == null) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.update(null, new LambdaUpdateWrapper<UsagePriceRule>()
                    .eq(UsagePriceRule::getId, rule.getId())
                    .set(UsagePriceRule::getPricingMode, rule.getPricingMode())
                    .set(UsagePriceRule::getIncludedQuantity, rule.getIncludedQuantity())
                    .set(UsagePriceRule::getUnitAmountMinor, rule.getUnitAmountMinor()));
        }

        for (int i = 0; i < request.tiers().size(); i++) {
            var item = request.tiers().get(i);

            UsagePriceTier tier = new UsagePriceTier();
            tier.setRuleId(rule.getId());
            tier.setTierNo(i + 1);
            tier.setUpToQuantity(item.upToQuantity());
            tier.setUnitAmountMinor(item.unitAmountMinor());

            tierMapper.insert(tier);
        }

        int changed = versionMapper.update(
                null,
                new LambdaUpdateWrapper<PriceVersion>()
                        .eq(PriceVersion::getId, versionId)
                        .eq(PriceVersion::getPriceId, priceId)
                        .eq(PriceVersion::getStatus, "DRAFT")
                        .eq(PriceVersion::getRevision, request.revision())
                        .setSql("revision = revision + 1")
        );

        if (changed != 1) {
            throw conflict("价格版本发生并发修改");
        }

        audit.record(null, userId, "USAGE_RULE_UPSERT",
                "USAGE_RULE", rule.getId().toString());

        return toRuleVO(rule, meter, request.revision() + 1);
    }

    @Transactional(readOnly = true)
    public List<UsageRuleVO> listRules(
            Long userId,
            Long productId,
            Long planId,
            Long priceId,
            Long versionId) {

        adminGuard.requireAdmin(userId);

        PriceVersion version =
                requireReadPath(productId, planId, priceId, versionId);

        return ruleMapper.selectList(
                new LambdaQueryWrapper<UsagePriceRule>()
                        .eq(UsagePriceRule::getPriceVersionId, versionId)
                        .orderByAsc(UsagePriceRule::getMeterId)
        ).stream().map(rule -> {
            UsageMeter meter = requireMeter(productId, rule.getMeterId());
            return toRuleVO(rule, meter, version.getRevision());
        }).toList();
    }

    @Transactional(readOnly = true)
    public UsageChargePreviewVO preview(
            Long userId,
            Long productId,
            Long planId,
            Long priceId,
            Long versionId,
            Long meterId,
            PreviewUsageRequest request) {

        adminGuard.requireAdmin(userId);

        PriceVersion version =
                requireReadPath(productId, planId, priceId, versionId);

        Price price = priceMapper.selectById(priceId);
        UsageMeter meter = requireMeter(productId, meterId);

        UsagePriceRule rule = findRule(versionId, meterId);
        if (rule == null) {
            throw notFound("当前价格版本没有该计量项的规则");
        }

        List<UsageChargeCalculator.Tier> tiers =
                loadTiers(rule.getId()).stream()
                        .map(t -> new UsageChargeCalculator.Tier(
                                t.getUpToQuantity(),
                                t.getUnitAmountMinor()))
                        .toList();

        UsageChargeCalculator.Charge charge;

        try {
            charge = UsageChargeCalculator.calculate(
                    PricingMode.valueOf(rule.getPricingMode()),
                    request.quantity(),
                    rule.getIncludedQuantity(),
                    rule.getUnitAmountMinor(),
                    tiers
            );
        } catch (ArithmeticException ex) {
            throw new BusinessException(
                    ErrorCode.INVALID_ARGUMENT,
                    "试算金额超出 BIGINT 可表示范围");
        }

        return new UsageChargePreviewVO(
                versionId,
                price.getCurrency(),
                meter.getMeterCode(),
                meter.getUnit(),
                version.getStatus(),
                PricingMode.valueOf(rule.getPricingMode()),
                request.quantity(),
                rule.getIncludedQuantity(),
                charge.billableQuantity(),
                charge.usageAmountMinor(),
                version.getBaseAmountMinor()
        );
    }

    private void validateRule(UpsertUsageRuleRequest request) {
        var mode = request.mode();
        var tiers = request.tiers();

        if (mode == PricingMode.PER_UNIT) {
            if (request.unitAmountMinor() == null || !tiers.isEmpty()) {
                throw invalid("PER_UNIT 需要单价，且不能包含阶梯");
            }
            return;
        }

        if (request.unitAmountMinor() != null || tiers.isEmpty()) {
            throw invalid("阶梯计费必须提供阶梯，不能提供统一单价");
        }

        long previous = 0;

        for (int i = 0; i < tiers.size(); i++) {
            Long upper = tiers.get(i).upToQuantity();
            boolean last = i == tiers.size() - 1;

            if (last) {
                if (upper != null) {
                    throw invalid("最后一档必须使用 null 表示无限上限");
                }
            } else {
                if (upper == null || upper <= previous) {
                    throw invalid("阶梯上限必须为严格递增的正整数");
                }
                previous = upper;
            }
        }
    }

    private void lockPrice(Long priceId, Long planId) {
        List<Long> rows = jdbc.queryForList("""
                SELECT plan_id
                FROM mb_price
                WHERE id = ?
                FOR UPDATE
                """, Long.class, priceId);

        if (rows.isEmpty() || !planId.equals(rows.getFirst())) {
            throw notFound("价格定义不存在");
        }
    }

    private VersionState currentVersion(Long priceId, Long versionId) {
        List<VersionState> rows = jdbc.query("""
                SELECT status, revision
                FROM mb_price_version
                WHERE id = ? AND price_id = ?
                FOR UPDATE
                """,
                (rs, index) -> new VersionState(
                        rs.getString("status"),
                        rs.getInt("revision")),
                versionId, priceId);

        if (rows.isEmpty()) {
            throw notFound("价格版本不存在");
        }

        return rows.getFirst();
    }

    private PriceVersion requireReadPath(
            Long productId, Long planId,
            Long priceId, Long versionId) {

        Plan plan = requirePlan(productId, planId);
        Price price = priceMapper.selectById(priceId);
        PriceVersion version = versionMapper.selectById(versionId);

        if (price == null
                || !planId.equals(price.getPlanId())
                || version == null
                || !priceId.equals(version.getPriceId())) {
            throw notFound("价格或价格版本不存在");
        }

        return version;
    }

    private void requireWritablePlan(Long productId, Long planId) {
        Plan plan = requirePlan(productId, planId);
        Product product = requireProduct(productId);

        if (!writableProduct(product.getStatus())
                || !("DRAFT".equals(plan.getStatus())
                || "ACTIVE".equals(plan.getStatus()))) {
            throw conflict("当前产品或套餐状态不允许修改用量规则");
        }
    }

    private boolean writableProduct(String status) {
        return "DRAFT".equals(status) || "ACTIVE".equals(status);
    }

    private Product requireProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw notFound("产品不存在");
        }
        return product;
    }

    private Plan requirePlan(Long productId, Long planId) {
        Plan plan = planMapper.selectById(planId);

        if (plan == null || !productId.equals(plan.getProductId())) {
            throw notFound("套餐不存在");
        }

        return plan;
    }

    private UsageMeter requireMeter(Long productId, Long meterId) {
        UsageMeter meter = meterMapper.selectById(meterId);

        if (meter == null
                || !productId.equals(meter.getProductId())) {
            throw notFound("计量项不存在");
        }

        return meter;
    }

    private UsagePriceRule findRule(Long versionId, Long meterId) {
        return ruleMapper.selectOne(
                new LambdaQueryWrapper<UsagePriceRule>()
                        .eq(UsagePriceRule::getPriceVersionId, versionId)
                        .eq(UsagePriceRule::getMeterId, meterId));
    }

    private List<UsagePriceTier> loadTiers(Long ruleId) {
        return tierMapper.selectList(
                new LambdaQueryWrapper<UsagePriceTier>()
                        .eq(UsagePriceTier::getRuleId, ruleId)
                        .orderByAsc(UsagePriceTier::getTierNo));
    }

    private UsageRuleVO toRuleVO(
            UsagePriceRule rule, UsageMeter meter, int revision) {

        List<UsageRuleVO.TierVO> tiers = loadTiers(rule.getId())
                .stream()
                .map(t -> new UsageRuleVO.TierVO(
                        t.getTierNo(),
                        t.getUpToQuantity(),
                        t.getUnitAmountMinor()))
                .toList();

        return new UsageRuleVO(
                rule.getId(),
                rule.getPriceVersionId(),
                meter.getId(),
                meter.getMeterCode(),
                PricingMode.valueOf(rule.getPricingMode()),
                rule.getIncludedQuantity(),
                rule.getUnitAmountMinor(),
                tiers,
                revision
        );
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_ARGUMENT, message);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.BUSINESS_CONFLICT, message);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    private record VersionState(String status, Integer revision) {
    }
}
