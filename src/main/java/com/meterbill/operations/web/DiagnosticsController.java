package com.meterbill.operations.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.product.security.PlatAdminGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "运行诊断", description = "平台管理员查看任务积压、连接池和进程指标")
@RestController
@RequiredArgsConstructor
public class DiagnosticsController {
    private final PlatAdminGuard guard;
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;

    @Operation(summary = "查询平台运行诊断快照")
    @GetMapping("/api/v1/platform/diagnostics")
    public ApiResponse<Map<String,Object>> snapshot(@AuthenticationPrincipal Jwt jwt) {
        guard.requireAdmin(Long.valueOf(jwt.getSubject()));
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("at",Instant.now());
        result.put("events",jdbc.queryForList("SELECT status,COUNT(*) count FROM mb_usage_event GROUP BY status"));
        result.put("outbox",jdbc.queryForList("SELECT status,COUNT(*) count FROM mb_outbox GROUP BY status"));
        result.put("oldestOutboxSeconds",jdbc.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP())),0) FROM mb_outbox WHERE status IN ('READY','PUBLISHING','RETRYABLE_FAILED')",Long.class));
        Map<String,Object> metrics=new LinkedHashMap<>();
        for(String name:java.util.List.of("hikaricp.connections.active","hikaricp.connections.idle","hikaricp.connections.pending",
                "hikaricp.connections.max","jvm.threads.live","jvm.memory.used","process.cpu.usage","system.cpu.usage",
                "http.server.requests","hikaricp.connections.acquire","hikaricp.connections.timeout")) {
            var meters=registry.find(name).meters();
            if(!meters.isEmpty()) metrics.put(name,meters.stream().map(m->{
                Map<String,Object> entry=new LinkedHashMap<>();entry.put("tags",m.getId().getTags().toString());
                m.measure().forEach(v->entry.put(v.getStatistic().toString(),v.getValue()));return entry;
            }).toList());
        }
        result.put("metrics",metrics);
        return ApiResponse.success(result);
    }
}
