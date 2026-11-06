package com.meterbill.account.api;

public interface AccountQueryApi {

    AccountRef require(Long tenantId, Long accountId);

    AccountRef requireDefault(Long tenantId);

    record AccountRef(Long id, Long tenantId, String currency, String timeZoneId, String status) {
    }
}
