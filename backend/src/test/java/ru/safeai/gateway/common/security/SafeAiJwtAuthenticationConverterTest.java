package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeAiJwtAuthenticationConverterTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private SafeAiJwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        converter = new SafeAiJwtAuthenticationConverter();
    }

    @Test
    void convert_shouldCreateAuthenticationFromValidJwt() {
        Jwt jwt = jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ADMIN", "USER")
        ));

        AbstractAuthenticationToken authentication =
                Objects.requireNonNull(converter.convert(jwt));

        assertThat(authentication.isAuthenticated()).isTrue();

        Object principalObject = Objects.requireNonNull(
                authentication.getPrincipal(),
                "Authentication principal не должен быть null"
        );

        assertThat(principalObject)
                .isInstanceOf(SafeAiUserPrincipal.class);

        SafeAiUserPrincipal principal =
                (SafeAiUserPrincipal) principalObject;

        assertThat(principal.getId()).isEqualTo(USER_ID);
        assertThat(principal.getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(principal.getEmail()).isEqualTo("admin@test.com");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.getTokenVersion()).isZero();

        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void convert_shouldThrowWhenRequiredClaimsAreMissing() {
        assertThatThrownBy(() -> converter.convert(jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ))))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("userId");

        assertThatThrownBy(() -> converter.convert(jwt(Map.of(
                "sub", USER_ID.toString(),
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ))))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("email");

        assertThatThrownBy(() -> converter.convert(jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "roles", List.of("ADMIN")
        ))))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("tokenVersion");
    }

    @Test
    void convert_shouldRejectInvalidUuidAndSubjectMismatch() {
        assertThatThrownBy(() -> converter.convert(jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", "not-uuid",
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ))))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("organizationId");

        assertThatThrownBy(() -> converter.convert(jwt(Map.of(
                "sub", UUID.randomUUID().toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ))))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void convert_shouldRejectUnknownRole() {
        assertThatThrownBy(() -> converter.convert(jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ROOT")
        ))))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("roles");
    }

    @Test
    void convert_shouldRejectFractionalNegativeAndNonNumericTokenVersion() {
        assertThatThrownBy(() -> converter.convert(
                jwtWithTokenVersion(1.9d)
        ))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("integral");

        assertThatThrownBy(() -> converter.convert(
                jwtWithTokenVersion(new BigDecimal("1.1"))
        ))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("integral");

        assertThatThrownBy(() -> converter.convert(
                jwtWithTokenVersion(-1)
        ))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("negative");

        assertThatThrownBy(() -> converter.convert(
                jwtWithTokenVersion("1")
        ))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("numeric");
    }

    @Test
    void convert_shouldRejectNonCanonicalEmail() {
        assertThatThrownBy(() -> converter.convert(jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", " Admin@Test.com ",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ))))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("email");
    }

    private Jwt jwtWithTokenVersion(Object tokenVersion) {
        return jwt(Map.of(
                "sub", USER_ID.toString(),
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "email", "admin@test.com",
                "roles", List.of("ADMIN"),
                "tokenVersion", tokenVersion
        ));
    }

    private Jwt jwt(Map<String, Object> claims) {
        return new Jwt(
                "token",
                Instant.parse("2026-06-12T12:00:00Z"),
                Instant.parse("2026-06-12T13:00:00Z"),
                Map.of("alg", "HS256"),
                claims
        );
    }
}
