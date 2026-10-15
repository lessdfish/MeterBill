
package com.meterbill.identity.web;

import com.meterbill.common.api.ApiResponse;
import com.meterbill.identity.security.TokenRevocationService;
import com.meterbill.identity.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "登录与账号", description = "登录、退出并读取当前账号信息")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final TokenRevocationService revocations;

    @Operation(summary = "账号密码登录")
    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(
            @Valid @RequestBody LoginRequest request) {

        return ApiResponse.success(
                authService.login(request.email(), request.password())
        );
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @AuthenticationPrincipal Jwt jwt) {

        revocations.logout(jwt);
        return ApiResponse.success(null);
    }

    @Operation(summary = "查询当前登录账号")
    @GetMapping("/me")
    public ApiResponse<AuthService.UserProfile> me(
            @AuthenticationPrincipal Jwt jwt) {

        return ApiResponse.success(
                authService.profile(Long.valueOf(jwt.getSubject()))
        );
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 190) String email,
            @NotBlank String password
    ) {
    }
}
