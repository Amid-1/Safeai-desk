package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
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
            Instant.parse("2026-06-12T12:00:00Z");

    private static final Duration TOKEN_LIFETIME =
            Duration.ofMinutes(60);

    private static final String ISSUER =
            "https://safeai.example.com";

    private static final String AUDIENCE =
            "safeai-desk-api";

    private static final String VALID_BASE64_SECRET =
            Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef"
                            .getBytes(StandardCharsets.UTF_8)
            );

    @Mock
    private JwtEncoder jwtEncoder;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties(
                VALID_BASE64_SECRET,
                TOKEN_LIFETIME.toMinutes(),
                ISSUER,
                AUDIENCE
        );

        Clock clock = Clock.fixed(
                NOW,
                ZoneOffset.UTC
        );

        jwtService = new JwtService(
                jwtEncoder,
                jwtProperties,
                clock
        );
    }

    @Test
    void generateTokenCreatesExpectedHeaderClaimsAndLifetime() {
        Jwt encodedJwt = Jwt.withTokenValue("encoded-token")
                .header("alg", "HS256")
                .header("typ", "JWT")
                .issuedAt(NOW)
                .expiresAt(NOW.plus(TOKEN_LIFETIME))
                .claim("test", "value")
                .build();

        when(jwtEncoder.encode(
                any(JwtEncoderParameters.class)
        )).thenReturn(encodedJwt);

        AccessTokenSubject subject =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        "admin@test.com",
                        7L,
                        Set.of(
                                "ROLE_USER",
                                "admin"
                        )
                );

        String token = jwtService.generateToken(subject);

        assertThat(token).isEqualTo("encoded-token");

        ArgumentCaptor<JwtEncoderParameters> captor =
                ArgumentCaptor.forClass(
                        JwtEncoderParameters.class
                );

        verify(jwtEncoder).encode(captor.capture());
        verifyNoMoreInteractions(jwtEncoder);

        JwtEncoderParameters parameters =
                captor.getValue();

        JwsHeader headers = Objects.requireNonNull(
                parameters.getJwsHeader(),
                "JWS header не должен быть null"
        );

        assertThat(headers.getAlgorithm())
                .isEqualTo(MacAlgorithm.HS256);

        assertThat(headers.getType())
                .isEqualTo("JWT");

        JwtClaimsSet claims = parameters.getClaims();

        assertThat(claims.getIssuer())
                .hasToString(ISSUER);

        assertThat(claims.getAudience())
                .containsExactly(AUDIENCE);

        assertThat(claims.getSubject())
                .isEqualTo(USER_ID.toString());

        assertThat(claims.getIssuedAt())
                .isEqualTo(NOW);

        assertThat(claims.getExpiresAt())
                .isEqualTo(
                        NOW.plus(TOKEN_LIFETIME)
                );

        assertThat(Duration.between(
                claims.getIssuedAt(),
                claims.getExpiresAt()
        )).isEqualTo(TOKEN_LIFETIME);

        assertThat(claims.getClaimAsString("email"))
                .isEqualTo("admin@test.com");

        assertThat(claims.getClaimAsString("userId"))
                .isEqualTo(USER_ID.toString());

        assertThat(
                claims.getClaimAsString(
                        "organizationId"
                )
        ).isEqualTo(ORGANIZATION_ID.toString());

        assertThat(
                claims.getClaimAsStringList("roles")
        ).containsExactly(
                "ADMIN",
                "USER"
        );

        Number tokenVersion =
                claims.getClaim("tokenVersion");

        assertThat(tokenVersion).isNotNull();
        assertThat(tokenVersion.longValue())
                .isEqualTo(7L);

        String tokenId =
                claims.getClaimAsString("jti");

        assertThat(tokenId).isNotBlank();
        assertThat(UUID.fromString(tokenId))
                .isNotNull();
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullSubjectIsRejectedBeforeEncoding() {
        assertThatThrownBy(() ->
                jwtService.generateToken(null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessage("subject не должен быть null");

        verifyNoInteractions(jwtEncoder);
    }
}