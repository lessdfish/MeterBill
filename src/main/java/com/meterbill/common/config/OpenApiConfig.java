package com.meterbill.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "BearerAuth";

    @Bean
    public OpenAPI meterBillOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MeterBill 业务接口文档")
                        .version("v1")
                        .description("MeterBill 计费平台接口。先调用登录接口取得令牌，再在右上角授权按钮中填写令牌。租户接口还需要 X-Tenant-Id 请求头。")
                        .contact(new Contact().name("MeterBill")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
                .components(new Components().addSecuritySchemes(
                        BEARER_AUTH,
                        new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录后获得的 JWT 访问令牌")));
    }

    @Bean
    public GroupedOpenApi identityApi() {
        return group("01-identity", "01 身份、租户与权限",
                "/api/v1/auth/**", "/api/v1/tenant/security/**",
                "/api/v1/tenant/current", "/api/v1/tenant/api-keys/**",
                "/api/v1/ingest/auth-check");
    }

    @Bean
    public GroupedOpenApi catalogApi() {
        return group("02-catalog", "02 产品、套餐与定价",
                "/api/v1/products/**", "/api/v1/platform/products/**");
    }

    @Bean
    public GroupedOpenApi subscriptionApi() {
        return group("03-subscriptions", "03 订阅管理",
                "/api/v1/tenant/subscriptions/**", "/api/v1/platform/customers/**");
    }

    @Bean
    public GroupedOpenApi usageApi() {
        return group("04-usage", "04 用量采集与查询",
                "/api/v1/usage-events/**", "/api/v1/tenant/usage-events/**",
                "/api/v1/ingest/usage-events/**");
    }

    @Bean
    public GroupedOpenApi billingApi() {
        return group("05-billing", "05 账单、支付与退款",
                "/api/v1/tenant/invoices/**", "/api/v1/tenant/payments/**",
                "/api/v1/tenant/refunds/**", "/api/v1/platform/payments/**",
                "/api/v1/platform/refunds/**", "/api/v1/platform/channel/**",
                "/api/v1/platform/billing/**");
    }

    @Bean
    public GroupedOpenApi operationsApi() {
        return group("06-operations", "06 对账与运行状态",
                "/api/v1/tenant/reconciliation/**", "/api/v1/platform/reconciliation/**",
                "/api/v1/platform/operations/**", "/api/v1/tenant/operations/**",
                "/api/v1/platform/diagnostics", "/api/v1/system/**");
    }

    @Bean
    public GroupedOpenApi consoleApi() {
        return group("07-console", "07 控制台聚合查询", "/api/v1/tenant/dashboard/**");
    }

    private GroupedOpenApi group(String id, String displayName, String... paths) {
        return GroupedOpenApi.builder()
                .group(id)
                .displayName(displayName)
                .pathsToMatch(paths)
                .build();
    }
}
