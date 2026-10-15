package com.meterbill.identity.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * ClassName:JwtConfig
 * Package:com.meterbill.identity.security
 * Description:
 *
 * @Author:lyp
 * @Create:2026/10/10 - 17:40
 * @Version: v1.0
 *
 */
@Configuration
public class JwtConfig {

    @Bean
    SecretKey jwtSecretKey(@Value("${meterbill.auth.jwt-secret-base64}") String encoded){
        byte[] bytes = Base64.getDecoder().decode(encoded);

        if (bytes.length<32) {
            throw new IllegalStateException("At Least 256 bits");
        }

        return new SecretKeySpec(bytes,"HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(
                new ImmutableSecret<>(jwtSecretKey)
        );
    }

    @Bean
    JwtDecoder jwtDecoder(
            SecretKey jwtSecretKey,
            @Value("${meterbill.auth.issuer}") String issuer,
            @Value("${meterbill.auth.audience}") String audience) {

        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(
                        org.springframework.security.oauth2.jose.jws
                                .MacAlgorithm.HS256)
                .build();

        var issuerValidator =
                JwtValidators.createDefaultWithIssuer(issuer);

        var audienceValidator = new JwtClaimValidator<List<String>>(
                "aud",
                values -> values != null && values.contains(audience)
        );

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        issuerValidator,
                        audienceValidator
                )
        );

        return decoder;
    }

}
