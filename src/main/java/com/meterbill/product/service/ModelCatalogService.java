package com.meterbill.product.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.SecurityAuditService;
import com.meterbill.product.security.PlatAdminGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelCatalogService {
    private final JdbcTemplate jdbc;
    private final PlatAdminGuard guard;
    private final SecurityAuditService audit;

    @Transactional
    public Long create(Long actor, Long product, String code, String name, List<Long> meters) {
        guard.requireAdmin(actor);
        var products = jdbc.queryForList("SELECT status FROM mb_product WHERE id=? FOR UPDATE", String.class, product);
        if (products.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "产品不存在");
        if (!List.of("DRAFT", "ACTIVE").contains(products.getFirst()))
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "产品不可新增模型");
        if (meters == null || meters.isEmpty() || meters.stream().distinct().count() != meters.size())
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "模型计量项不能为空或重复");
        for (Long meter : meters) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM mb_usage_meter WHERE id=? AND product_id=?", Integer.class, meter, product) != 1)
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "计量项不属于产品");
            if (jdbc.queryForObject("SELECT COUNT(*) FROM mb_model_meter WHERE meter_id=?", Integer.class, meter) != 0)
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "新模型必须使用专属计量项以隔离额度与价格");
        }
        try {
            jdbc.update("INSERT INTO mb_ai_model(product_id,model_code,name) VALUES (?,?,?)", product, code, name);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "模型编码已存在");
        }
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (Long meter : meters) jdbc.update("INSERT INTO mb_model_meter(model_id,meter_id) VALUES (?,?)", id, meter);
        audit.record(null, actor, "MODEL_CREATE", "AI_MODEL", id.toString());
        return id;
    }

    @Transactional(readOnly = true)
    public List<Model> list(Long actor, Long product) {
        guard.requireAdmin(actor);
        return jdbc.query("SELECT id,model_code,name FROM mb_ai_model WHERE product_id=? ORDER BY id",
                (rs, n) -> new Model(rs.getLong(1), rs.getString(2), rs.getString(3),
                        jdbc.queryForList("SELECT meter_id FROM mb_model_meter WHERE model_id=? ORDER BY meter_id", Long.class, rs.getLong(1))), product);
    }
    public record Model(Long id, String modelCode, String name, List<Long> meterIds) { }
}
