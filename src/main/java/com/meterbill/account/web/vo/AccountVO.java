package com.meterbill.account.web.vo;

import java.time.Instant;

public record AccountVO(Long id, Long tenantId, String externalKey, String name,
                        String email, String currency, String timeZoneId,
                        String status, boolean defaultAccount, int version,
                        Instant createdAt) {
}
