package com.meterbill.identity.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ClassName:SecurityAuditService
 * Package:com.meterbill.identity.service
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/11 - 10:59
 * @Version: v1.0
 *
 */
@Service
@RequiredArgsConstructor
public class SecurityAuditService {
    private final JdbcTemplate jdbc;

    public void record(Long tenantId,Long actorId,String action,String targetType,String targetId){
        insert(tenantId,actorId,action,targetType,targetId,"SUCCESS");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLogin(Long userId,boolean success){
        insert(null, userId, "USER_LOGIN",
                "USER",
                userId == null ? null : userId.toString(),
                success ? "SUCCESS" : "FAILURE");
    }

    private void insert(
            Long tenantId,
            Long actorId,
            String action,
            String targetType,
            String targetId,
            String outcome) {

        jdbc.update("""
                INSERT INTO mb_security_audit
                    (tenant_id, actor_type, actor_id, action,
                     target_type, target_id, outcome, request_id)
                VALUES (?, 'USER', ?, ?, ?, ?, ?, ?)
                """,
                tenantId, actorId, action, targetType,
                targetId, outcome, MDC.get("requestId"));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> list(Long tenantId, int limit) {
        return jdbc.query("""
                SELECT id, actor_type, actor_id, action,
                       target_type, target_id, outcome,
                       request_id, created_at
                FROM mb_security_audit
                WHERE tenant_id = ?
                ORDER BY id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new AuditEntry(
                        rs.getLong("id"),
                        rs.getString("actor_type"),
                        rs.getObject("actor_id", Long.class),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("outcome"),
                        rs.getString("request_id"),
                        rs.getObject("created_at", LocalDateTime.class)
                ),
                tenantId, limit);
    }

    public record AuditEntry(
            Long id,
            String actorType,
            Long actorId,
            String action,
            String targetType,
            String targetId,
            String outcome,
            String requestId,
            LocalDateTime createdAt) {
    }
}
