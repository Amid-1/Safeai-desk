package ru.safeai.gateway.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class JwtCodecConfiguration {

    private static final Duration CLOCK_SKEW =
            Duration.ofSeconds(30);

    private static final String REQUIRED_TOKEN_TYPE =
            "JWT";

    @Bean
    SecretKey jwtSecretKey(
            JwtProperties properties
    ) {
        return properties.secretKey();
    }

    @Bean
    JwtEncoder jwtEncoder(
            SecretKey jwtSecretKey
    ) {
        return NimbusJwtEncoder
                .withSecretKey(jwtSecretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            SecretKey jwtSecretKey,
            JwtProperties properties,
            Clock clock
    ) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder
                        .withSecretKey(
                                jwtSecretKey
                        )
                        .macAlgorithm(
                                MacAlgorithm.HS256
                        )
                        /*
                         * typ проверяется явно ниже.
                         * Это не позволяет silently принять
                         * token без typ.
                         */
                        .validateType(false)
                        .build();

        decoder.setJwtValidator(
                createJwtValidator(
                        properties,
                        clock
                )
        );

        return decoder;
    }

    private OAuth2TokenValidator<Jwt>
    createJwtValidator(
            JwtProperties properties,
            Clock clock
    ) {
        return new DelegatingOAuth2TokenValidator<>(
                createTimestampValidator(clock),
                new JwtIssuerValidator(
                        properties.issuer()
                ),
                new JwtAudienceValidator(
                        properties.audience()
                ),
                createTypeValidator()
        );
    }

    private JwtTimestampValidator
    createTimestampValidator(
            Clock clock
    ) {
        JwtTimestampValidator validator =
                new JwtTimestampValidator(
                        CLOCK_SKEW
                );

        validator.setClock(clock);
        validator.setAllowEmptyExpiryClaim(
                false
        );
        validator.setAllowEmptyNotBeforeClaim(
                true
        );

        return validator;
    }

    private JwtTypeValidator
    createTypeValidator() {
        JwtTypeValidator validator =
                new JwtTypeValidator(
                        REQUIRED_TOKEN_TYPE
                );

        /*
         * JwtTypeValidator.jwt() здесь не используем:
         * в нашем контракте typ обязателен.
         */
        validator.setAllowEmpty(false);

        return validator;
    }
}
