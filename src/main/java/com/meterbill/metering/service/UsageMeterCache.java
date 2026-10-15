package com.meterbill.metering.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "meterbill.redis-cache", name = "enabled", havingValue = "true")
public class UsageMeterCache {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public UsageMeterCache(JdbcTemplate jdbc, StringRedisTemplate redis,
                           @Value("${meterbill.redis-cache.usage-meter-ttl:10m}") Duration ttl) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.ttl = ttl;
    }

    public Long findMeterId(Long productId, String meterCode, String modelCode) {
        String key = cacheKey(productId, meterCode, modelCode);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                try {
                    return Long.valueOf(cached);
                } catch (NumberFormatException ex) {
                    redis.delete(key);
                    log.debug("Discarded invalid cached meter mapping for product {}", productId);
                }
            }
        } catch (DataAccessException ex) {
            log.debug("Redis meter cache unavailable; reading product {} meter from MySQL", productId);
        }

        List<Long> ids = jdbc.query("""
                SELECT m.id FROM mb_usage_meter m JOIN mb_model_meter mm ON mm.meter_id=m.id JOIN mb_ai_model a ON a.id=mm.model_id WHERE m.product_id = ? AND m.meter_code = ? AND a.product_id=m.product_id AND a.model_code=?
                """, (rs, row) -> rs.getLong(1), productId, meterCode, modelCode);
        if (ids.isEmpty()) {
            return null;
        }
        Long meterId = ids.getFirst();
        try {
            redis.opsForValue().set(key, meterId.toString(), ttl);
        } catch (DataAccessException ex) {
            log.debug("Redis meter cache write unavailable; keeping MySQL result for product {}", productId);
        }
        return meterId;
    }

    public void invalidate(Long productId, String meterCode, String modelCode) {
        try {
            redis.delete(cacheKey(productId, meterCode, modelCode));
        } catch (DataAccessException ex) {
            log.debug("Redis meter cache invalidation unavailable for product {}", productId);
        }
    }

    String cacheKey(Long productId, String meterCode, String modelCode) {
        String encodedCode = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(meterCode.getBytes(StandardCharsets.UTF_8));
        return "meterbill:usage-meter:" + productId + ":" + encodedCode + ":model:" + Base64.getUrlEncoder().withoutPadding().encodeToString(modelCode.getBytes(StandardCharsets.UTF_8));
    }
}
