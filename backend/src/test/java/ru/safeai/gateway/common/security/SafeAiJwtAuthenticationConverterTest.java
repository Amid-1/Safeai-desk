package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
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
                "sub", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", ORGANIZATION_ID.toString(),
                "enabled", true,
                "scope", "ROLE_ADMIN ROLE_USER"
        ));

        AbstractAuthenticationToken authentication =
                Objects.requireNonNull(converter.convert(jwt));

        assertThat(authentication.isAuthenticated()).isTrue();

        Object principalObject = authentication.getPrincipal();

        assertThat(principalObject).isInstanceOf(SafeAiUserPrincipal.class);

        SafeAiUserPrincipal principal = (SafeAiUserPrincipal) principalObject;

        assertThat(principal)
                .extracting(
                        SafeAiUserPrincipal::getId,
                        SafeAiUserPrincipal::getOrganizationId,
                        SafeAiUserPrincipal::getEmail,
                        SafeAiUserPrincipal::isEnabled
                )
                .containsExactly(
                        USER_ID,
                        ORGANIZATION_ID,
                        "admin@test.com",
                        true
                );

        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void convert_shouldThrowBadJwtExceptionWhenUserIdIsMissing() {
        Jwt jwt = jwt(Map.of(
                "sub", "admin@test.com",
                "organizationId", ORGANIZATION_ID.toString(),
                "scope", "ROLE_ADMIN"
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void convert_shouldThrowBadJwtExceptionWhenOrganizationIdIsInvalid() {
        Jwt jwt = jwt(Map.of(
                "sub", "admin@test.com",
                "userId", USER_ID.toString(),
                "organizationId", "not-uuid",
                "scope", "ROLE_ADMIN"
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("organizationId");
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