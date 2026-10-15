
package com.meterbill.identity.service;

import com.meterbill.common.exception.BusinessException;
import com.meterbill.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TenantRoleService {

    private final JdbcTemplate jdbc;
    private final SecurityAuditService audit;

    private static final Set<String> ASSIGNABLE =
            Set.of("ADMIN", "VIEWER");

    @Transactional(readOnly = true)
    public List<String> roles(Long tenantId, Long userId) {
        return jdbc.queryForList("""
                SELECT DISTINCT r.role_code
                FROM mb_tenant_member m
                JOIN mb_member_role mr
                  ON mr.tenant_id = m.tenant_id
                 AND mr.member_id = m.id
                JOIN mb_role r
                  ON r.tenant_id = mr.tenant_id
                 AND r.id = mr.role_id
                WHERE m.tenant_id = ?
                  AND m.user_id = ?
                  AND m.status = 'ACTIVE'
                ORDER BY r.role_code
                """, String.class, tenantId, userId);
    }

    @Transactional(readOnly = true)
    public List<String> permissions(Long tenantId, Long userId) {
        return jdbc.queryForList("""
                SELECT DISTINCT rp.permission_code
                FROM mb_tenant_member m
                JOIN mb_member_role mr
                  ON mr.tenant_id = m.tenant_id
                 AND mr.member_id = m.id
                JOIN mb_role r
                  ON r.tenant_id = mr.tenant_id
                 AND r.id = mr.role_id
                JOIN mb_role_permission rp ON rp.role_id = r.id
                WHERE m.tenant_id = ?
                  AND m.user_id = ?
                  AND m.status = 'ACTIVE'
                ORDER BY rp.permission_code
                """, String.class, tenantId, userId);
    }

    public void require(
            Long tenantId, Long userId, String permission) {

        if (!permissions(tenantId, userId).contains(permission)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    @Transactional
    public void changeRole(
            Long tenantId,
            Long actorUserId,
            Long targetMemberId,
            String newRole,
            boolean platformAdmin) {

        requireManager(tenantId, actorUserId, platformAdmin);
        validateAssignable(newRole);

        List<Long> users = jdbc.queryForList("""
                SELECT user_id FROM mb_tenant_member
                WHERE tenant_id = ?
                  AND id = ?
                  AND status = 'ACTIVE'
                FOR UPDATE
                """, Long.class, tenantId, targetMemberId);

        if (users.isEmpty() || users.getFirst().equals(actorUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        Long ownerCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mb_member_role mr
                JOIN mb_role r
                  ON r.tenant_id = mr.tenant_id
                 AND r.id = mr.role_id
                WHERE mr.tenant_id = ?
                  AND mr.member_id = ?
                  AND r.role_code = 'OWNER'
                """, Long.class, tenantId, targetMemberId);

        if (ownerCount != null && ownerCount > 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        Long roleId = jdbc.queryForObject("""
                SELECT id FROM mb_role
                WHERE tenant_id = ? AND role_code = ?
                """, Long.class, tenantId, newRole);

        jdbc.update("""
                DELETE FROM mb_member_role
                WHERE tenant_id = ? AND member_id = ?
                """, tenantId, targetMemberId);

        jdbc.update("""
                INSERT INTO mb_member_role
                    (tenant_id, member_id, role_id)
                VALUES (?, ?, ?)
                """, tenantId, targetMemberId, roleId);

        audit.record(
                tenantId, actorUserId, "MEMBER_ROLE_CHANGE",
                "MEMBER", targetMemberId.toString());
    }

    @Transactional
    public Long addExistingMember(Long tenantId, Long actorUserId, String email,
                                  String roleCode, boolean platformAdmin) {
        requireManager(tenantId, actorUserId, platformAdmin);
        validateAssignable(roleCode);

        List<Long> users = jdbc.queryForList("""
                SELECT id FROM mb_user
                WHERE LOWER(email) = ? AND status = 'ACTIVE'
                """, Long.class, email.trim().toLowerCase(Locale.ROOT));
        if (users.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "未找到可加入的有效用户");
        }
        Long userId = users.getFirst();
        List<MemberState> existing = jdbc.query("""
                SELECT id, status FROM mb_tenant_member
                WHERE tenant_id = ? AND user_id = ?
                FOR UPDATE
                """, (rs, row) -> new MemberState(rs.getLong("id"), rs.getString("status")),
                tenantId, userId);

        Long memberId;
        String action;
        if (existing.isEmpty()) {
            jdbc.update("""
                    INSERT INTO mb_tenant_member (tenant_id, user_id, status)
                    VALUES (?, ?, 'ACTIVE')
                    """, tenantId, userId);
            memberId = jdbc.queryForObject("""
                    SELECT id FROM mb_tenant_member WHERE tenant_id = ? AND user_id = ?
                    """, Long.class, tenantId, userId);
            action = "MEMBER_ADD";
        } else {
            MemberState member = existing.getFirst();
            if ("ACTIVE".equals(member.status())) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "该用户已经是当前租户成员");
            }
            memberId = member.id();
            jdbc.update("""
                    UPDATE mb_tenant_member
                    SET status = 'ACTIVE', version = version + 1
                    WHERE tenant_id = ? AND id = ?
                    """, tenantId, memberId);
            action = "MEMBER_REACTIVATE";
        }

        assignRole(tenantId, memberId, roleCode);
        audit.record(tenantId, actorUserId, action, "MEMBER", memberId.toString());
        return memberId;
    }

    @Transactional
    public void disableMember(Long tenantId, Long actorUserId, Long targetMemberId,
                              boolean platformAdmin) {
        requireManager(tenantId, actorUserId, platformAdmin);
        List<Long> users = jdbc.queryForList("""
                SELECT user_id FROM mb_tenant_member
                WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """, Long.class, tenantId, targetMemberId);
        if (users.isEmpty() || users.getFirst().equals(actorUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (hasRole(tenantId, targetMemberId, "OWNER")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "租户所有者不能被停用");
        }
        jdbc.update("""
                UPDATE mb_tenant_member
                SET status = 'DISABLED', version = version + 1
                WHERE tenant_id = ? AND id = ?
                """, tenantId, targetMemberId);
        jdbc.update("DELETE FROM mb_member_role WHERE tenant_id = ? AND member_id = ?",
                tenantId, targetMemberId);
        audit.record(tenantId, actorUserId, "MEMBER_DISABLE", "MEMBER", targetMemberId.toString());
    }

    private void requireManager(Long tenantId, Long actorUserId, boolean platformAdmin) {
        if (!platformAdmin) {
            require(tenantId, actorUserId, "MEMBER_ROLE_MANAGE");
        }
    }

    private void validateAssignable(String roleCode) {
        if (!ASSIGNABLE.contains(roleCode)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "只允许分配 ADMIN 或 VIEWER");
        }
    }

    private boolean hasRole(Long tenantId, Long memberId, String roleCode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mb_member_role mr
                JOIN mb_role r ON r.tenant_id = mr.tenant_id AND r.id = mr.role_id
                WHERE mr.tenant_id = ? AND mr.member_id = ? AND r.role_code = ?
                """, Long.class, tenantId, memberId, roleCode);
        return count != null && count > 0;
    }

    private void assignRole(Long tenantId, Long memberId, String roleCode) {
        Long roleId = jdbc.queryForObject("""
                SELECT id FROM mb_role WHERE tenant_id = ? AND role_code = ?
                """, Long.class, tenantId, roleCode);
        jdbc.update("DELETE FROM mb_member_role WHERE tenant_id = ? AND member_id = ?",
                tenantId, memberId);
        jdbc.update("""
                INSERT INTO mb_member_role (tenant_id, member_id, role_id)
                VALUES (?, ?, ?)
                """, tenantId, memberId, roleId);
    }

    private record MemberState(Long id, String status) {
    }
}
