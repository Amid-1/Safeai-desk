package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SafeAiJwtAuthenticationConverterTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final Instant ISSUED_AT =
            Instant.parse("2026-06-12T12:00:00Z");

    private static final Instant EXPIRES_AT =
            Instant.parse("2026-06-12T12:05:00Z");

    private static final String RAW_TOKEN =
            "raw-secret-token";

    private final SafeAiJwtAuthenticationConverter converter =
            new SafeAiJwtAuthenticationConverter();

    @Test
    void validJwtCreatesAuthenticatedPrincipalWithoutCredentials() {
        AbstractAuthenticationToken authentication =
                converter.convert(validJwt());

        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();

        // Raw JWT не сохраняется в Authentication credentials.
        assertThat(authentication.getCredentials())
                .isNull();

        SafeAiUserPrincipal principal = assertInstanceOf(
                SafeAiUserPrincipal.class,
                authentication.getPrincipal()
        );

        assertThat(principal.getId())
                .isEqualTo(USER_ID);

        assertThat(principal.getOrganizationId())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(principal.getEmail())
                .isEqualTo("user@example.com");

        assertThat(principal.getTokenVersion())
                .isEqualTo(1L);

        assertThat(principal.isEnabled())
                .isTrue();

        /*
         * Access-token principal не содержит password hash.
         * Пустая строка используется для совместимости
         * с non-null контрактом UserDetails.
         */
        assertThat(principal.getPassword())
                .isEmpty();

        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly(
                        "ROLE_ADMIN",
                        "ROLE_USER"
                );
    }

    @Test
    void principalDoesNotContainRawJwtOrEmailInToString() {
        AbstractAuthenticationToken authentication =
                converter.convert(validJwt());

        SafeAiUserPrincipal principal = assertInstanceOf(
                SafeAiUserPrincipal.class,
                authentication.getPrincipal()
        );

        assertThat(authentication.toString())
                .doesNotContain(RAW_TOKEN);

        assertThat(principal.toString())
                .doesNotContain(
                        RAW_TOKEN,
                        "user@example.com",
                        "password",
                        "hash"
                );
    }

    @Test
    void missingSubjectIsRejected() {
        assertThatThrownBy(() ->
                converter.convert(jwt(
                        null,
                        validClaims()
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void blankSubjectIsRejected() {
        assertThatThrownBy(() ->
                converter.convert(jwt(
                        " ",
                        validClaims()
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("subject");
    }

    @Test
    void subjectDifferentFromUserIdIsRejected() {
        assertThatThrownBy(() ->
                converter.convert(jwt(
                        UUID.randomUUID().toString(),
                        validClaims()
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining(
                        "subject does not match userId"
                );
    }

    @Test
    void missingUserIdIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.remove("userId");

        assertMissingClaim(claims, "userId");
    }

    @Test
    void missingOrganizationIdIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.remove("organizationId");

        assertMissingClaim(claims, "organizationId");
    }

    @Test
    void missingEmailIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.remove("email");

        assertMissingClaim(claims, "email");
    }

    @Test
    void missingTokenVersionIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.remove("tokenVersion");

        assertThatThrownBy(() ->
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("tokenVersion");
    }

    @Test
    void invalidUserIdUuidIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.put("userId", "not-a-uuid");

        assertThatThrownBy(() ->
                converter.convert(jwt(
                        "not-a-uuid",
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void invalidOrganizationIdUuidIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.put("organizationId", "not-a-uuid");

        assertThatThrownBy(() ->
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("organizationId");
    }

    @Test
    void uppercaseEmailIsRejected() {
        assertInvalidEmail("User@example.com");
    }

    @Test
    void emailWithExternalSpacesIsRejected() {
        assertInvalidEmail(" user@example.com ");
    }

    @Test
    void emailLongerThan255CharactersIsRejected() {
        String email = "a".repeat(244) + "@example.com";

        assertThat(email.length()).isGreaterThan(255);
        assertInvalidEmail(email);
    }

    @Test
    void missingRolesIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.remove("roles");

        assertThatThrownBy(() ->
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("roles");
    }

    @Test
    void emptyRolesAreRejected() {
        Map<String, Object> claims = validClaims();
        claims.put("roles", List.of());

        assertThatThrownBy(() ->
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("roles");
    }

    @Test
    void unknownRoleIsRejected() {
        Map<String, Object> claims = validClaims();
        claims.put("roles", List.of("ROOT"));

        assertThatThrownBy(() ->
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("roles");
    }

    @Test
    void roleNamesAreCanonicalizedAndDeduplicated() {
        Map<String, Object> claims = validClaims();
        claims.put(
                "roles",
                List.of(
                        "user",
                        "ROLE_ADMIN",
                        "USER"
                )
        );

        AbstractAuthenticationToken authentication =
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ));

        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly(
                        "ROLE_ADMIN",
                        "ROLE_USER"
                );
    }

    @Test
    void negativeTokenVersionIsRejected() {
        assertInvalidTokenVersion(
                -1L,
                "negative"
        );
    }

    @Test
    void fractionalDoubleTokenVersionIsRejected() {
        assertInvalidTokenVersion(
                1.5D,
                "integral long"
        );
    }

    @Test
    void fractionalFloatTokenVersionIsRejected() {
        assertInvalidTokenVersion(
                1.5F,
                "integral long"
        );
    }

    @Test
    void fractionalBigDecimalTokenVersionIsRejected() {
        assertInvalidTokenVersion(
                new BigDecimal("1.1"),
                "integral long"
        );
    }

    @Test
    void bigIntegerOverflowIsRejected() {
        assertInvalidTokenVersion(
                BigInteger.valueOf(Long.MAX_VALUE)
                        .add(BigInteger.ONE),
                "integral long"
        );
    }

    @Test
    void bigDecimalOverflowIsRejected() {
        assertInvalidTokenVersion(
                new BigDecimal(Long.MAX_VALUE)
                        .add(BigDecimal.ONE),
                "integral long"
        );
    }

    @Test
    void nanTokenVersionIsRejected() {
        assertInvalidTokenVersion(
                Double.NaN,
                "integral long"
        );
    }

    @Test
    void positiveInfinityTokenVersionIsRejected() {
        assertInvalidTokenVersion(
                Double.POSITIVE_INFINITY,
                "integral long"
        );
    }

    @Test
    void nonNumericTokenVersionIsRejected() {
        assertInvalidTokenVersion(
                "1",
                "not numeric"
        );
    }

    @Test
    void supportedIntegralNumberTypesAreAccepted() {
        List<Number> versions = List.of(
                (byte) 1,
                (short) 2,
                3,
                4L,
                BigInteger.valueOf(5),
                new BigDecimal("6"),
                7.0D,
                8.0F
        );

        for (Number version : versions) {
            Map<String, Object> claims = validClaims();
            claims.put("tokenVersion", version);

            AbstractAuthenticationToken authentication =
                    converter.convert(jwt(
                            USER_ID.toString(),
                            claims
                    ));

            SafeAiUserPrincipal principal = assertInstanceOf(
                    SafeAiUserPrincipal.class,
                    authentication.getPrincipal()
            );

            assertThat(principal.getTokenVersion())
                    .isEqualTo(version.longValue());
        }
    }

    private void assertMissingClaim(
            Map<String, Object> claims,
            String claimName
    ) {
        assertThatThrownBy(() ->
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining(claimName);
    }

    private void assertInvalidEmail(String email) {
        Map<String, Object> claims = validClaims();
        claims.put("email", email);

        assertThatThrownBy(() ->
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining("canonical");
    }

    private void assertInvalidTokenVersion(
            Object tokenVersion,
            String expectedMessage
    ) {
        Map<String, Object> claims = validClaims();
        claims.put("tokenVersion", tokenVersion);

        assertThatThrownBy(() ->
                converter.convert(jwt(
                        USER_ID.toString(),
                        claims
                ))
        )
                .isInstanceOf(BadJwtException.class)
                .hasMessageContaining(expectedMessage);
    }

    private Jwt validJwt() {
        return jwt(
                USER_ID.toString(),
                validClaims()
        );
    }

    private Map<String, Object> validClaims() {
        Map<String, Object> claims = new HashMap<>();

        claims.put("userId", USER_ID.toString());
        claims.put(
                "organizationId",
                ORGANIZATION_ID.toString()
        );
        claims.put("email", "user@example.com");
        claims.put(
                "roles",
                new ArrayList<>(List.of("USER", "ADMIN"))
        );
        claims.put("tokenVersion", 1L);

        return claims;
    }

    private Jwt jwt(
            String subject,
            Map<String, Object> claims
    ) {
        Jwt.Builder builder = Jwt.withTokenValue(RAW_TOKEN)
                .header("alg", "HS256")
                .issuedAt(ISSUED_AT)
                .expiresAt(EXPIRES_AT);

        if (subject != null) {
            builder.subject(subject);
        }

        claims.forEach(builder::claim);

        return builder.build();
    }
}