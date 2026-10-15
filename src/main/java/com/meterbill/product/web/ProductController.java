package com.meterbill.product.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.common.api.PageResponse;
import com.meterbill.product.service.ProductService;
import com.meterbill.product.web.dto.CreateProduct;
import com.meterbill.product.web.vo.ProductVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * ClassName:ProductController
 * Package:com.meterbill.product.web
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/11 - 13:38
 * @Version: v1.0
 *
 */
@Tag(name = "产品与套餐", description = "平台管理员维护可售产品和套餐")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;

    @Operation(summary = "新增产品")
    @PostMapping
    public ApiResponse<Long> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateProduct create){
        return ApiResponse.success(productService.create(
                Long.valueOf(jwt.getSubject()),
                create
        ));
    }

    @Operation(summary = "查询产品列表")
    @GetMapping
    public ApiResponse<PageResponse<ProductVO>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size
            ){
        return ApiResponse.success(productService.list(
                Long.valueOf(jwt.getSubject()),
                page,
                size
        ));

    }

    @Operation(summary = "发布产品", description = "产品至少包含一个可用套餐后才能发布")
    @PostMapping("/{productId}/publish")
    public ApiResponse<ProductVO> publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long productId) {
        return ApiResponse.success(productService.publish(
                Long.valueOf(jwt.getSubject()), productId));
    }

}
