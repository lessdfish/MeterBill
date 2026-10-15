package com.meterbill.metering.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.ApiKeyAuthenticationFilter.ApiKeyPrincipal;
import com.meterbill.metering.service.UsageEventService;
import com.meterbill.metering.web.dto.UsageEventRequest;
import com.meterbill.metering.web.vo.UsageEventVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用量采集", description = "可靠接收用量事件并查询处理状态")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ingest/usage-events")
public class UsageEventController {

    private final UsageEventService usageEvents;

    @Operation(summary = "接收用量事件")
    @PostMapping
    public ResponseEntity<ApiResponse<UsageEventVO>> accept(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @Valid @RequestBody UsageEventRequest request) {
        UsageEventVO accepted = usageEvents.accept(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(accepted));
    }

    @Operation(summary = "查询用量事件处理状态")
    @GetMapping("/{sourceId}/{eventId}")
    public ApiResponse<UsageEventVO> get(
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            @PathVariable String sourceId,
            @PathVariable String eventId) {
        return ApiResponse.success(
                usageEvents.get(principal.tenantId(), sourceId, eventId));
    }
}
