package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
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

    private static final String ISSUER =
            "https://safeai.example.com";

    private static final String AUDIENCE =
            "safeai-api";

    private static final String ACTIVE_KEY_ID =
            "safeai-test-active";

    private static final KeyPair ACTIVE_KEY_PAIR =
            generateRsaKeyPair();

    private final JwtProperties properties =
            createProperties(
                    ACTIVE_KEY_PAIR
            );

    private final JwtCodecConfiguration configuration =
            new JwtCodecConfiguration();

    private final JwtRsaKeyRing keyRing =
            new JwtRsaKeyRing(
                    properties
            );

    private final JwtEncoder encoder =
            configuration.jwtEncoder(
                    keyRing
            );

    private final JwtDecoder decoder =
            configuration.jwtDecoder(
                    keyRing,
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

        UUID userId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        AccessTokenSubject subject =
                new AccessTokenSubject(
                        userId,
                        organizationId,
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

        assertThat(
                jwt.getHeaders()
                        .get("kid")
        ).isEqualTo(
                ACTIVE_KEY_ID
        );

        assertThat(jwt.getIssuer())
                .hasToString(
                        ISSUER
                );

        assertThat(jwt.getAudience())
                .containsExactly(
                        AUDIENCE
                );

        assertThat(jwt.getSubject())
                .isEqualTo(
                        userId.toString()
                );

        assertThat(
                jwt.getClaimAsString(
                        "userId"
                )
        ).isEqualTo(
                userId.toString()
        );

        assertThat(
                jwt.getClaimAsString(
                        "organizationId"
                )
        ).isEqualTo(
                organizationId.toString()
        );

        assertThat(
                jwt.getClaims()
        ).doesNotContainKey(
                "email"
        );

        Number tokenVersion =
                Objects.requireNonNull(
                        jwt.getClaim(
                                "tokenVersion"
                        ),
                        "tokenVersion claim must be present"
                );

        assertThat(
                tokenVersion.longValue()
        ).isEqualTo(4L);

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

        assertThat(
                jwt.getClaimAsStringList(
                        "roles"
                )
        ).containsExactly(
                "USER"
        );
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
                                                SignatureAlgorithm.RS256
                                        )
                                        .keyId(
                                                properties.activeKeyId()
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
                                                SignatureAlgorithm.RS256
                                        )
                                        .keyId(
                                                properties.activeKeyId()
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
    void tokenSignedWithDifferentRsaKeyIsRejected() {
        JwtProperties otherProperties =
                createProperties(
                        generateRsaKeyPair()
                );

        JwtRsaKeyRing otherKeyRing =
                new JwtRsaKeyRing(
                        otherProperties
                );

        JwtEncoder otherEncoder =
                configuration.jwtEncoder(
                        otherKeyRing
                );

        String rawToken =
                otherEncoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader
                                        .with(
                                                SignatureAlgorithm.RS256
                                        )
                                        /*
                                         * Намеренно используем тот же kid:
                                         * decoder найдёт public key по kid,
                                         * но signature verification должна
                                         * провалиться из-за другой RSA пары.
                                         */
                                        .keyId(
                                                properties.activeKeyId()
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
                                        SignatureAlgorithm.RS256
                                )
                                .keyId(
                                        properties.activeKeyId()
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

    private static JwtProperties createProperties(
            KeyPair keyPair
    ) {
        return new JwtProperties(
                15L,
                ISSUER,
                AUDIENCE,
                ACTIVE_KEY_ID,
                List.of(
                        new JwtProperties.KeyEntry(
                                ACTIVE_KEY_ID,
                                toPublicPem(keyPair),
                                toPrivatePem(keyPair)
                        )
                )
        );
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance(
                            "RSA"
                    );

            /*
             * Production может использовать 3072/4096.
             * Для unit-теста 2048 достаточно и соответствует
             * минимальному invariant JwtRsaKeyRing.
             */
            generator.initialize(2048);

            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "RSA KeyPairGenerator недоступен",
                    exception
            );
        }
    }

    private static String toPublicPem(
            KeyPair keyPair
    ) {
        return pem(
                "PUBLIC KEY",
                keyPair.getPublic()
                        .getEncoded()
        );
    }

    private static String toPrivatePem(
            KeyPair keyPair
    ) {
        return pem(
                "PRIVATE KEY",
                keyPair.getPrivate()
                        .getEncoded()
        );
    }

    private static String pem(
            String type,
            byte[] encoded
    ) {
        String body =
                Base64.getMimeEncoder(
                                64,
                                "\n".getBytes(
                                        StandardCharsets.US_ASCII
                                )
                        )
                        .encodeToString(
                                encoded
                        );

        return "-----BEGIN "
                + type
                + "-----\n"
                + body
                + "\n-----END "
                + type
                + "-----";
    }
}