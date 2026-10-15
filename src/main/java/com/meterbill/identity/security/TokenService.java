package com.meterbill.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ClassName:TokenService
 * Package:com.meterbill.identity.security
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 17:45
 * @Version: v1.0
 *
 */
@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final Duration ttl;

    public TokenService(JwtEncoder encoder,
                        @Value("${meterbill.auth.issuer}") String issuer,
                        @Value("${meterbill.auth.audience}") String audience,
                        @Value("${meterbill.auth.access-token-minutes}") long minutes) {

        if (minutes<=0) {
            throw new IllegalArgumentException("Token ttl be over time");
        }
        this.encoder = encoder;
        this.issuer = issuer;
        this.audience = audience;
        this.ttl = Duration.ofMinutes(minutes);
    }

    public TokenResult issue(Long userId){
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token =encoder.encode(JwtEncoderParameters.from(header,claims)).getTokenValue();

        return new TokenResult(token,ttl.toSeconds());
    }

    public record TokenResult(String accessToken,long expiresIn){

    }
}
