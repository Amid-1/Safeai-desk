package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

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

        Object principalObject = authentication.getPrincipal();

        if (!(principalObject instanceof SafeAiUserPrincipal principal)) {
            throw new AssertionError("Principal должен быть SafeAiUserPrincipal");
        }

        assertThat(principal.getId()).isEqualTo(USER_ID);
        assertThat(principal.getOrganizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(principal.getEmail()).isEqualTo("admin@test.com");
        assertThat(principal.isEnabled()).isTrue();
        assertThat(principal.getTokenVersion()).isEqualTo(0L);

        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void convert_shouldThrowBadJwtExceptionWhenUserIdIsMissing() {
        Jwt jwt = jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void convert_shouldThrowBadJwtExceptionWhenEmailIsMissing() {
        Jwt jwt = jwt(Map.of(
                "sub", USER_ID.toString(),
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("email");
    }

    @Test
    void convert_shouldThrowBadJwtExceptionWhenTokenVersionIsMissing() {
        Jwt jwt = jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "roles", List.of("ADMIN")
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("tokenVersion");
    }

    @Test
    void convert_shouldThrowBadJwtExceptionWhenOrganizationIdIsInvalid() {
        Jwt jwt = jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", "not-uuid",
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void convert_shouldThrowBadJwtExceptionWhenSubjectDoesNotMatchUserId() {
        Jwt jwt = jwt(Map.of(
                "sub", UUID.randomUUID().toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ADMIN")
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void convert_shouldThrowBadJwtExceptionWhenRoleIsUnknown() {
        Jwt jwt = jwt(Map.of(
                "sub", USER_ID.toString(),
                "email", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "tokenVersion", 0L,
                "roles", List.of("ROOT")
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("roles");
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