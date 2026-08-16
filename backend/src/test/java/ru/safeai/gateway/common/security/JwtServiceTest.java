package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-12T12:00:00Z"
            );

    private static final Duration TOKEN_LIFETIME =
            Duration.ofMinutes(60);

    private static final String ISSUER =
            "https://safeai.example.com";

    private static final String AUDIENCE =
            "safeai-desk-api";

    private static final String ACTIVE_KEY_ID =
            "safeai-test-active";

    /*
     * JwtServiceTest мокает JwtEncoder и не строит JwtRsaKeyRing,
     * поэтому здесь достаточно syntactically non-blank test-only
     * key material. Корректность PEM/RSA parsing покрывается
     * JwtRsaKeyRing/JwtCodecConfiguration integration tests.
     */
    private static final String TEST_PUBLIC_KEY =
            "test-only-public-key";

    private static final String TEST_PRIVATE_KEY =
            "test-only-private-key";

    @Mock
    private JwtEncoder jwtEncoder;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties =
                new JwtProperties(
                        TOKEN_LIFETIME.toMinutes(),
                        ISSUER,
                        AUDIENCE,
                        ACTIVE_KEY_ID,
                        List.of(
                                new JwtProperties.KeyEntry(
                                        ACTIVE_KEY_ID,
                                        TEST_PUBLIC_KEY,
                                        TEST_PRIVATE_KEY
                                )
                        )
                );

        Clock clock =
                Clock.fixed(
                        NOW,
                        ZoneOffset.UTC
                );

        jwtService =
                new JwtService(
                        jwtEncoder,
                        jwtProperties,
                        clock
                );
    }

    @Test
    void generateTokenCreatesExpectedHeaderClaimsAndLifetime() {
        Jwt encodedJwt =
                Jwt.withTokenValue(
                                "encoded-token"
                        )
                        .header(
                                "alg",
                                "RS256"
                        )
                        .header(
                                "typ",
                                "JWT"
                        )
                        .header(
                                "kid",
                                ACTIVE_KEY_ID
                        )
                        .issuedAt(NOW)
                        .expiresAt(
                                NOW.plus(
                                        TOKEN_LIFETIME
                                )
                        )
                        .claim(
                                "test",
                                "value"
                        )
                        .build();

        when(
                jwtEncoder.encode(
                        any(
                                JwtEncoderParameters.class
                        )
                )
        ).thenReturn(encodedJwt);

        AccessTokenSubject subject =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        7L,
                        12L,
                        Set.of(
                                "ROLE_USER",
                                "admin"
                        )
                );

        String token =
                jwtService.generateToken(
                        subject
                );

        assertThat(token)
                .isEqualTo(
                        "encoded-token"
                );

        ArgumentCaptor<JwtEncoderParameters>
                captor =
                ArgumentCaptor.forClass(
                        JwtEncoderParameters.class
                );

        verify(jwtEncoder)
                .encode(
                        captor.capture()
                );

        verifyNoMoreInteractions(
                jwtEncoder
        );

        JwtEncoderParameters parameters =
                captor.getValue();

        JwsHeader headers =
                Objects.requireNonNull(
                        parameters.getJwsHeader(),
                        "JWS header не должен быть null"
                );

        assertThat(
                headers.getAlgorithm()
        ).isEqualTo(
                SignatureAlgorithm.RS256
        );

        assertThat(
                headers.getType()
        ).isEqualTo(
                "JWT"
        );

        assertThat(
                headers.getKeyId()
        ).isEqualTo(
                ACTIVE_KEY_ID
        );

        JwtClaimsSet claims =
                parameters.getClaims();

        assertThat(
                claims.getIssuer()
        ).hasToString(
                ISSUER
        );

        assertThat(
                claims.getAudience()
        ).containsExactly(
                AUDIENCE
        );

        assertThat(
                claims.getSubject()
        ).isEqualTo(
                USER_ID.toString()
        );

        assertThat(
                claims.getIssuedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                claims.getExpiresAt()
        ).isEqualTo(
                NOW.plus(
                        TOKEN_LIFETIME
                )
        );

        assertThat(
                Duration.between(
                        Objects.requireNonNull(
                                claims.getIssuedAt(),
                                "issuedAt не должен быть null"
                        ),
                        Objects.requireNonNull(
                                claims.getExpiresAt(),
                                "expiresAt не должен быть null"
                        )
                )
        ).isEqualTo(
                TOKEN_LIFETIME
        );

        assertThat(
                claims.getClaims()
        ).doesNotContainKey(
                "email"
        );

        assertThat(
                claims.getClaimAsString(
                        "userId"
                )
        ).isEqualTo(
                USER_ID.toString()
        );

        assertThat(
                claims.getClaimAsString(
                        "organizationId"
                )
        ).isEqualTo(
                ORGANIZATION_ID.toString()
        );

        assertThat(
                claims.getClaimAsStringList(
                        "roles"
                )
        ).containsExactly(
                "ADMIN",
                "USER"
        );

        Number tokenVersion =
                Objects.requireNonNull(
                        claims.getClaim(
                                "tokenVersion"
                        ),
                        "tokenVersion claim must be present"
                );

        assertThat(
                tokenVersion.longValue()
        ).isEqualTo(7L);

        Number organizationAuthVersion =
                Objects.requireNonNull(
                        claims.getClaim(
                                "organizationAuthVersion"
                        ),
                        "organizationAuthVersion claim must be present"
                );

        assertThat(
                organizationAuthVersion.longValue()
        ).isEqualTo(12L);

        String tokenId =
                claims.getClaimAsString(
                        "jti"
                );

        assertThat(tokenId)
                .isNotBlank();

        assertThat(
                UUID.fromString(
                        Objects.requireNonNull(
                                tokenId,
                                "jti не должен быть null"
                        )
                )
        ).isNotNull();
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullSubjectIsRejectedBeforeEncoding() {
        assertThatThrownBy(() ->
                jwtService.generateToken(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "subject не должен быть null"
                );

        verifyNoInteractions(
                jwtEncoder
        );
    }
}