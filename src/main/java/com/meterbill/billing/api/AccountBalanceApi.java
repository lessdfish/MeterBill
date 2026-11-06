package com.meterbill.billing.api;

public interface AccountBalanceApi {

    AccountBalance balance(Long tenantId, Long accountId);

    record AccountBalance(Long accountId, String currency, long invoicedMinor,
                          long adjustmentMinor, long paidMinor,
                          long refundedMinor, long balanceMinor) {
    }
}
