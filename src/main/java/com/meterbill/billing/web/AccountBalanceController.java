package com.meterbill.billing.web;

import com.meterbill.billing.api.AccountBalanceApi;
import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/tenant/accounts")
public class AccountBalanceController {

    private final AccountBalanceApi balances;

    @GetMapping("/{accountId}/billing-balance")
    public ApiResponse<AccountBalanceApi.AccountBalance> balance(@PathVariable Long accountId) {
        return ApiResponse.success(balances.balance(TenantContext.requiredTenantId(), accountId));
    }
}
