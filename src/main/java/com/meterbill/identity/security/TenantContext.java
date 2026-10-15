package com.meterbill.identity.security;

/**
 * ClassName:TenantContext
 * Package:com.meterbill.identity.security
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 18:18
 * @Version: v1.0
 *
 */
public class TenantContext {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static Long requiredTenantId() {
        Long tenantId = CURRENT.get();

        if (tenantId == null) {
            throw new IllegalStateException(
                    "No verified tenant in current request");
        }

        return tenantId;
    }
}
