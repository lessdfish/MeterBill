package com.meterbill.identity.security;

import com.meterbill.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * ClassName:TenantAccessFilter
 * Package:com.meterbill.identity.security
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 18:20
 * @Version: v1.0
 *
 */
@RequiredArgsConstructor
public class TenantAccessFilter extends OncePerRequestFilter {

    private final TenantAccessService tenantAccessService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        return !path.startsWith("/api/v1/tenant/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken token)) {
            SecurityErrorWriter.write(response, ErrorCode.UNAUTHORIZED);
            return;
        }

        String header = request.getHeader("X-Tenant-Id");
        Long tenantId;

        try {
            if (header == null || !header.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            tenantId = Long.valueOf(header);
        } catch (NumberFormatException ex) {
            SecurityErrorWriter.write(response, ErrorCode.TENANT_REQUIRED);
            return;
        }

        Long userId = Long.valueOf(token.getToken().getSubject());

        if (!tenantAccessService.canAccess(userId, tenantId)) {
            SecurityErrorWriter.write(response, ErrorCode.TENANT_FORBIDDEN);
            return;
        }

        try {
            TenantContext.set(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
