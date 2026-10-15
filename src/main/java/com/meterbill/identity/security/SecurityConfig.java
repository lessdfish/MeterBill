
package com.meterbill.identity.security;

import com.meterbill.common.exception.ErrorCode;
import com.meterbill.identity.persistence.entity.User;
import com.meterbill.identity.persistence.mapper.UserMapper;
import com.meterbill.identity.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserMapper userMapper;
    private final TenantAccessService tenantAccessService;
    private final ApiKeyService apiKeys;
    private final TokenRevocationService revocations;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/system/ping",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        .requestMatchers("/actuator/prometheus")
                        .authenticated()

                        .requestMatchers("/api/v1/ingest/**")
                        .hasAuthority("METERING_INGEST")

                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                            Long userId;

                            try {
                                userId = Long.valueOf(token.getSubject());
                            } catch (NumberFormatException ex) {
                                throw new BadCredentialsException(
                                        "Invalid token subject");
                            }

                            User user = userMapper.selectById(userId);

                            if (user == null
                                    || !"ACTIVE".equals(user.getStatus())
                                    || revocations.isRevoked(token.getId())) {

                                throw new BadCredentialsException(
                                        "Invalid or revoked token");
                            }

                            return new JwtAuthenticationToken(
                                    token, List.of()
                            );
                        }))

                        .authenticationEntryPoint(
                                (request, response, ex) ->
                                        SecurityErrorWriter.write(
                                                response,
                                                ErrorCode.UNAUTHORIZED)
                        ))

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                (request, response, error) ->
                                        SecurityErrorWriter.write(
                                                response,
                                                ErrorCode.UNAUTHORIZED)
                        )
                        .accessDeniedHandler(
                                (request, response, error) ->
                                        SecurityErrorWriter.write(
                                                response,
                                                ErrorCode.FORBIDDEN)
                        ))

                .addFilterBefore(
                        new ApiKeyAuthenticationFilter(apiKeys),
                        BearerTokenAuthenticationFilter.class
                )

                .addFilterAfter(
                        new TenantAccessFilter(tenantAccessService),
                        BearerTokenAuthenticationFilter.class
                )

                .build();
    }
}
