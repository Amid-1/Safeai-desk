package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeAiJwtAuthenticationConverterHardeningTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final Instant NOW =
            Instant.parse("2026-08-21T12:00:00Z");

    private final SafeAiJwtAuthenticationConverter converter =
            new SafeAiJwtAuthenticationConverter();

    @Test
    void exactlyOneRoleIsAccepted() {
        var authentication = converter.convert(
                validJwt(List.of("USER"))
        );

        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void multipleRolesAreRejected() {
        Jwt jwt = validJwt(
                List.of(
                        "SUPER_ADMIN",
                        "USER"
                )
        );

        assertThatThrownBy(() ->
                converter.convert(jwt)
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining(
                        "exactly one role"
                );
    }

    @Test
    void missingJtiIsRejected() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(NOW)
                .expiresAt(
                        NOW.plus(
                                15,
                                ChronoUnit.MINUTES
                        )
                )
                .claim(
                        "userId",
                        USER_ID.toString()
                )
                .claim(
                        "organizationId",
                        ORGANIZATION_ID.toString()
                )
                .claim("tokenVersion", 0L)
                .claim("organizationAuthVersion", 0L)
                .claim("roles", List.of("USER"))
                .build();

        assertThatThrownBy(() ->
                converter.convert(jwt)
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("jti");
    }

    @Test
    void nonUuidJtiIsRejected() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(NOW)
                .expiresAt(
                        NOW.plus(
                                15,
                                ChronoUnit.MINUTES
                        )
                )
                .claim("jti", "not-a-uuid")
                .claim(
                        "userId",
                        USER_ID.toString()
                )
                .claim(
                        "organizationId",
                        ORGANIZATION_ID.toString()
                )
                .claim("tokenVersion", 0L)
                .claim("organizationAuthVersion", 0L)
                .claim("roles", List.of("USER"))
                .build();

        assertThatThrownBy(() ->
                converter.convert(jwt)
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("jti");
    }

    @Test
    void missingIssuedAtIsRejected() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .expiresAt(
                        NOW.plus(
                                15,
                                ChronoUnit.MINUTES
                        )
                )
                .claim(
                        "jti",
                        UUID.randomUUID().toString()
                )
                .claim(
                        "userId",
                        USER_ID.toString()
                )
                .claim(
                        "organizationId",
                        ORGANIZATION_ID.toString()
                )
                .claim("tokenVersion", 0L)
                .claim("organizationAuthVersion", 0L)
                .claim("roles", List.of("USER"))
                .build();

        assertThatThrownBy(() ->
                converter.convert(jwt)
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("iat");
    }

    private Jwt validJwt(
            List<String> roles
    ) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(NOW)
                .expiresAt(
                        NOW.plus(
                                15,
                                ChronoUnit.MINUTES
                        )
                )
                .claim(
                        "jti",
                        UUID.randomUUID().toString()
                )
                .claim(
                        "userId",
                        USER_ID.toString()
                )
                .claim(
                        "organizationId",
                        ORGANIZATION_ID.toString()
                )
                .claim("tokenVersion", 0L)
                .claim("organizationAuthVersion", 0L)
                .claim("roles", roles)
                .build();
    }
}
