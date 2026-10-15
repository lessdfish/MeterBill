
package com.meterbill.identity.security;

import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeys;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        return !path.startsWith("/api/v1/ingest/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {

        String bearer = request.getHeader("Authorization");

        if (bearer != null && !bearer.isBlank()) {
            SecurityErrorWriter.write(
                    response, ErrorCode.UNAUTHORIZED);
            return;
        }

        ApiKeyService.AuthenticatedKey key =
                apiKeys.authenticate(request.getHeader("X-API-Key"));

        if (key == null) {
            SecurityErrorWriter.write(
                    response, ErrorCode.UNAUTHORIZED);
            return;
        }

        var principal = new ApiKeyPrincipal(
                key.keyId(), key.tenantId());

        var authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority(
                                "METERING_INGEST"))
                );

        SecurityContextHolder.getContext()
                .setAuthentication(authentication);

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    public record ApiKeyPrincipal(Long keyId, Long tenantId) {
    }
}
