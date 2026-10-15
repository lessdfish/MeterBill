package com.meterbill.product.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.product.service.ModelCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Tag(name = "产品模型目录", description = "维护产品模型及其计量项绑定")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/platform/products/{productId}/models")
public class ModelCatalogController {
    private final ModelCatalogService models;
    @Operation(summary = "新增产品模型")
    @PostMapping
    public ApiResponse<Long> create(@AuthenticationPrincipal Jwt jwt, @PathVariable Long productId,
                                   @Valid @RequestBody Request request) {
        return ApiResponse.success(models.create(Long.valueOf(jwt.getSubject()), productId,
                request.modelCode(), request.name(), request.meterIds()));
    }
    @Operation(summary = "查询产品模型")
    @GetMapping
    public ApiResponse<List<ModelCatalogService.Model>> list(@AuthenticationPrincipal Jwt jwt, @PathVariable Long productId) {
        return ApiResponse.success(models.list(Long.valueOf(jwt.getSubject()), productId));
    }
    public record Request(@NotBlank @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$") String modelCode,
                          @NotBlank @Size(max=128) String name,
                          @NotEmpty @Size(max=32) List<@NotNull @Positive Long> meterIds) { }
}
