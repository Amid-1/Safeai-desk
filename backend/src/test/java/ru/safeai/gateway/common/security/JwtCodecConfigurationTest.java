package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtCodecConfigurationTest {

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-12T12:00:00Z"
            );

    private static final Clock CLOCK =
            Clock.fixed(
                    NOW,
                    ZoneOffset.UTC
            );

    private static final byte[] SECRET_BYTES =
            "0123456789abcdef0123456789abcdef"
                    .getBytes(StandardCharsets.UTF_8);

    private final JwtProperties properties =
            new JwtProperties(
                    Base64.getEncoder()
                            .encodeToString(
                                    SECRET_BYTES
                            ),
                    15L,
                    "https://safeai.example.com",
                    "safeai-api"
            );

    private final JwtCodecConfiguration configuration =
            new JwtCodecConfiguration();

    private final SecretKey secretKey =
            configuration.jwtSecretKey(
                    properties
            );

    private final JwtEncoder encoder =
            configuration.jwtEncoder(
                    secretKey
            );

    private final JwtDecoder decoder =
            configuration.jwtDecoder(
                    secretKey,
                    properties,
                    CLOCK
            );

    @Test
    void issuedTokenPassesAllValidation() {
        JwtService service =
                new JwtService(
                        encoder,
                        properties,
                        CLOCK
                );

        AccessTokenSubject subject =
                new AccessTokenSubject(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "user@example.com",
                        4L,
                        9L,
                        Set.of("USER")
                );

        var jwt =
                decoder.decode(
                        service.generateToken(
                                subject
                        )
                );

        assertThat(
                jwt.getHeaders()
                        .get("typ")
        ).isEqualTo("JWT");

        assertThat(jwt.getIssuer())
                .hasToString(
                        "https://safeai.example.com"
                );

        assertThat(jwt.getAudience())
                .containsExactly(
                        "safeai-api"
                );

        assertThat(
                jwt.getClaimAsString(
                        "email"
                )
        ).isEqualTo(
                "user@example.com"
        );

        Number organizationAuthVersion =
                Objects.requireNonNull(
                        jwt.getClaim(
                                "organizationAuthVersion"
                        ),
                        "organizationAuthVersion claim must be present"
                );

        assertThat(
                organizationAuthVersion.longValue()
        ).isEqualTo(9L);
    }

    @Test
    void tokenWithWrongAudienceIsRejected() {
        assertRejected(
                token(
                        properties.issuer(),
                        List.of("other-api"),
                        NOW,
                        NOW.plusSeconds(300),
                        "JWT"
                )
        );
    }

    @Test
    void tokenWithWrongIssuerIsRejected() {
        assertRejected(
                token(
                        "https://attacker.example.com",
                        List.of(
                                properties.audience()
                        ),
                        NOW,
                        NOW.plusSeconds(300),
                        "JWT"
                )
        );
    }

    @Test
    void expiredTokenIsRejected() {
        assertRejected(
                token(
                        properties.issuer(),
                        List.of(
                                properties.audience()
                        ),
                        NOW.minusSeconds(600),
                        NOW.minusSeconds(60),
                        "JWT"
                )
        );
    }

    @Test
    void tokenWithoutExpiryIsRejected() {
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(
                                properties.issuer()
                        )
                        .audience(
                                List.of(
                                        properties.audience()
                                )
                        )
                        .issuedAt(NOW)
                        .subject(
                                UUID.randomUUID()
                                        .toString()
                        )
                        .build();

        String rawToken =
                encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader
                                        .with(
                                                MacAlgorithm.HS256
                                        )
                                        .type("JWT")
                                        .build(),
                                claims
                        )
                ).getTokenValue();

        assertRejected(rawToken);
    }

    @Test
    void tokenWithoutTypeIsRejected() {
        JwtClaimsSet claims =
                baseClaims();

        String rawToken =
                encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader
                                        .with(
                                                MacAlgorithm.HS256
                                        )
                                        .build(),
                                claims
                        )
                ).getTokenValue();

        assertRejected(rawToken);
    }

    @Test
    void tokenWithUnexpectedTypeIsRejected() {
        assertRejected(
                token(
                        properties.issuer(),
                        List.of(
                                properties.audience()
                        ),
                        NOW,
                        NOW.plusSeconds(300),
                        "at+jwt"
                )
        );
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        SecretKey otherKey =
                new SecretKeySpec(
                        "fedcba9876543210fedcba9876543210"
                                .getBytes(
                                        StandardCharsets.UTF_8
                                ),
                        "HmacSHA256"
                );

        JwtEncoder otherEncoder =
                configuration.jwtEncoder(
                        otherKey
                );

        String rawToken =
                otherEncoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader
                                        .with(
                                                MacAlgorithm.HS256
                                        )
                                        .type("JWT")
                                        .build(),
                                baseClaims()
                        )
                ).getTokenValue();

        assertRejected(rawToken);
    }

    private JwtClaimsSet baseClaims() {
        return JwtClaimsSet.builder()
                .issuer(
                        properties.issuer()
                )
                .audience(
                        List.of(
                                properties.audience()
                        )
                )
                .issuedAt(NOW)
                .expiresAt(
                        NOW.plusSeconds(300)
                )
                .subject(
                        UUID.randomUUID()
                                .toString()
                )
                .build();
    }

    private String token(
            String issuer,
            List<String> audience,
            Instant issuedAt,
            Instant expiresAt,
            String type
    ) {
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(audience)
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .subject(
                                UUID.randomUUID()
                                        .toString()
                        )
                        .build();

        return encoder.encode(
                JwtEncoderParameters.from(
                        JwsHeader
                                .with(
                                        MacAlgorithm.HS256
                                )
                                .type(type)
                                .build(),
                        claims
                )
        ).getTokenValue();
    }

    private void assertRejected(
            String rawToken
    ) {
        assertThatThrownBy(() ->
                decoder.decode(rawToken)
        ).isInstanceOf(
                JwtException.class
        );
    }
}
