package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Mock
    private JwtEncoder jwtEncoder;

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties(
                "1234567890123456789012345678901212345678901234567890123456789012",
                60,
                "safeai-test",
                "safeai-desk-api"
        );

        jwtService = new JwtService(jwtEncoder, jwtProperties);
    }

    @Test
    void generateToken_shouldCreateJwtWithExpectedClaims() {
        Jwt encodedJwt = new Jwt(
                "encoded-token",
                Instant.parse("2026-06-12T12:00:00Z"),
                Instant.parse("2026-06-12T13:00:00Z"),
                Map.of("alg", "HS256"),
                Map.of("test", "value")
        );

        when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
                .thenReturn(encodedJwt);

        SafeAiUserPrincipal principal = new SafeAiUserPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                "admin@test.com",
                "",
                true,
                7L,
                List.of(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_USER")
                )
        );

        String token = jwtService.generateToken(principal);

        assertThat(token).isEqualTo("encoded-token");

        ArgumentCaptor<JwtEncoderParameters> captor =
                ArgumentCaptor.forClass(JwtEncoderParameters.class);

        verify(jwtEncoder).encode(captor.capture());

        JwtClaimsSet claims = captor.getValue().getClaims();

        assertThat(claims.getClaimAsString("iss")).isEqualTo("safeai-test");
        assertThat(claims.getAudience()).containsExactly("safeai-desk-api");
        assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());

        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiresAt()).isNotNull();
        assertThat(claims.getExpiresAt()).isAfter(claims.getIssuedAt());

        assertThat(claims.getClaimAsString("email"))
                .isEqualTo("admin@test.com");

        assertThat(claims.getClaimAsString("userId"))
                .isEqualTo(USER_ID.toString());

        assertThat(claims.getClaimAsString("organizationId"))
                .isEqualTo(ORGANIZATION_ID.toString());

        Number tokenVersion = claims.getClaim("tokenVersion");
        assertThat(tokenVersion.longValue()).isEqualTo(7L);

        List<String> roles = claims.getClaim("roles");
        assertThat(roles).containsExactly("ADMIN", "USER");

        String jti = claims.getClaimAsString("jti");

        assertThat(jti).isNotBlank();

        UUID parsedJti = UUID.fromString(jti);
        assertThat(parsedJti).isNotNull();
    }
}