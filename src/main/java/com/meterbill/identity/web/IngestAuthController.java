
package com.meterbill.identity.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.ApiKeyAuthenticationFilter.ApiKeyPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "用量接入鉴权", description = "检查用量接入密钥是否有效")
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestAuthController {

    @Operation(summary = "验证用量接入密钥")
    @GetMapping("/auth-check")
    public ApiResponse<Map<String, Long>> check(
            @AuthenticationPrincipal ApiKeyPrincipal principal) {

        return ApiResponse.success(Map.of(
                "tenantId", principal.tenantId(),
                "keyId", principal.keyId()
        ));
    }
}
