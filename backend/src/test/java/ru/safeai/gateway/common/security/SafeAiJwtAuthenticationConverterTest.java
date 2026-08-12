package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SafeAiJwtAuthenticationConverterTest {

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

    private final SafeAiJwtAuthenticationConverter
            converter =
            new SafeAiJwtAuthenticationConverter();

    @Test
    void validJwtCreatesAuthenticatedPrincipalWithoutCredentials() {
        AbstractAuthenticationToken authentication =
                convert(
                        USER_ID.toString(),
                        validClaims()
                );

        assertThat(authentication)
                .isNotNull();

        assertThat(
                authentication.isAuthenticated()
        ).isTrue();

        assertThat(
                authentication.getCredentials()
        ).isNull();

        SafeAiUserPrincipal principal =
                assertInstanceOf(
                        SafeAiUserPrincipal.class,
                        authentication
                                .getPrincipal()
                );

        assertThat(principal.getId())
                .isEqualTo(USER_ID);

        assertThat(
                principal.getOrganizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(principal.getTokenVersion())
                .isEqualTo(4L);

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(8L);

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_USER"
        );

        assertThat(
                principal.getPassword()
        ).isNull();
    }

    @Test
    void rawJwtAndEmailAreNotExposedByPrincipalToString() {
        SafeAiUserPrincipal principal =
                convertPrincipal(
                        USER_ID.toString(),
                        validClaims()
                );

        assertThat(principal.toString())
                .doesNotContain(
                        "raw-secret-token"
                )
                .doesNotContain(
                        "user@example.com"
                );
    }

    @Test
    void missingOrganizationAuthVersionIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.remove(
                "organizationAuthVersion"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "organizationAuthVersion"
        );
    }

    @Test
    void negativeOrganizationAuthVersionIsRejected() {
        assertInvalidOrganizationAuthVersion(
                -1L,
                "negative"
        );
    }

    @Test
    void fractionalDoubleOrganizationAuthVersionIsRejected() {
        assertInvalidOrganizationAuthVersion(
                1.5D,
                "integral long"
        );
    }

    @Test
    void fractionalFloatOrganizationAuthVersionIsRejected() {
        assertInvalidOrganizationAuthVersion(
                1.5F,
                "integral long"
        );
    }

    @Test
    void fractionalBigDecimalOrganizationAuthVersionIsRejected() {
        assertInvalidOrganizationAuthVersion(
                new BigDecimal("1.5"),
                "integral long"
        );
    }

    @Test
    void organizationAuthVersionBigIntegerOverflowIsRejected() {
        assertInvalidOrganizationAuthVersion(
                BigInteger
                        .valueOf(
                                Long.MAX_VALUE
                        )
                        .add(
                                BigInteger.ONE
                        ),
                "integral long"
        );
    }

    @Test
    void organizationAuthVersionBigDecimalOverflowIsRejected() {
        assertInvalidOrganizationAuthVersion(
                new BigDecimal(
                        Long.MAX_VALUE
                ).add(
                        BigDecimal.ONE
                ),
                "integral long"
        );
    }

    @Test
    void nanOrganizationAuthVersionIsRejected() {
        assertInvalidOrganizationAuthVersion(
                Double.NaN,
                "integral long"
        );
    }

    @Test
    void positiveInfinityOrganizationAuthVersionIsRejected() {
        assertInvalidOrganizationAuthVersion(
                Double.POSITIVE_INFINITY,
                "integral long"
        );
    }

    @Test
    void nonNumericOrganizationAuthVersionIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "organizationAuthVersion",
                "8"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "not numeric"
        );
    }

    @Test
    void supportedIntegralOrganizationVersionTypesAreAccepted() {
        List<Number> versions =
                List.of(
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
            Map<String, Object> claims =
                    validClaims();

            claims.put(
                    "organizationAuthVersion",
                    version
            );

            SafeAiUserPrincipal principal =
                    convertPrincipal(
                            USER_ID.toString(),
                            claims
                    );

            assertThat(
                    principal
                            .getOrganizationAuthVersion()
            ).isEqualTo(
                    version.longValue()
            );
        }
    }

    @Test
    void missingTokenVersionIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.remove(
                "tokenVersion"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "tokenVersion"
        );
    }

    @Test
    void fractionalTokenVersionIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "tokenVersion",
                1.5D
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "integral long"
        );
    }

    @Test
    void tokenVersionOverflowIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "tokenVersion",
                BigInteger
                        .valueOf(
                                Long.MAX_VALUE
                        )
                        .add(
                                BigInteger.ONE
                        )
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "integral long"
        );
    }

    @Test
    void subjectMismatchIsRejected() {
        assertRejected(
                UUID.randomUUID()
                        .toString(),
                validClaims(),
                "subject does not match userId"
        );
    }

    @Test
    void unknownRoleIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of("ROOT")
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "roles contain invalid values"
        );
    }

    @Test
    void nonStringRoleIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of(123)
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "roles contain invalid values"
        );
    }

    @Test
    void roleNamesAreCanonicalizedAndDeduplicated() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of(
                        "user",
                        "ROLE_USER",
                        "admin"
                )
        );

        SafeAiUserPrincipal principal =
                convertPrincipal(
                        USER_ID.toString(),
                        claims
                );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_ADMIN",
                "ROLE_USER"
        );
    }

    @Test
    void invalidUserUuidIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "userId",
                "not-a-uuid"
        );

        assertRejected(
                "not-a-uuid",
                claims,
                "valid UUID"
        );
    }

    @Test
    void invalidOrganizationUuidIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "organizationId",
                "not-a-uuid"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "valid UUID"
        );
    }

    @Test
    void nonCanonicalEmailIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "email",
                "User@example.com"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "not canonical"
        );
    }

    @Test
    void missingSubjectIsRejected() {
        assertThatThrownBy(() ->
                converter.convert(
                        jwt(
                                null,
                                validClaims()
                        )
                )
        )
                .isInstanceOf(
                        BadJwtException.class
                )
                .hasMessageContaining(
                        "subject is missing"
                );
    }

    private void assertInvalidOrganizationAuthVersion(
            Object value,
            String expectedMessage
    ) {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "organizationAuthVersion",
                value
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                expectedMessage
        );
    }

    private void assertRejected(
            String subject,
            Map<String, Object> claims,
            String expectedMessage
    ) {
        assertThatThrownBy(() ->
                converter.convert(
                        jwt(
                                subject,
                                claims
                        )
                )
        )
                .isInstanceOf(
                        BadJwtException.class
                )
                .hasMessageContaining(
                        expectedMessage
                );
    }

    private Map<String, Object>
    validClaims() {
        Map<String, Object> claims =
                new LinkedHashMap<>();

        claims.put(
                "userId",
                USER_ID.toString()
        );

        claims.put(
                "organizationId",
                ORGANIZATION_ID.toString()
        );

        claims.put(
                "email",
                "user@example.com"
        );

        claims.put(
                "roles",
                List.of("USER")
        );

        claims.put(
                "tokenVersion",
                4L
        );

        claims.put(
                "organizationAuthVersion",
                8L
        );

        return claims;
    }

    private AbstractAuthenticationToken convert(
            String subject,
            Map<String, Object> claims
    ) {
        return Objects.requireNonNull(
                converter.convert(
                        jwt(
                                subject,
                                claims
                        )
                ),
                "converter returned null authentication"
        );
    }

    private SafeAiUserPrincipal convertPrincipal(
            String subject,
            Map<String, Object> claims
    ) {
        AbstractAuthenticationToken authentication =
                convert(
                        subject,
                        claims
                );

        return assertInstanceOf(
                SafeAiUserPrincipal.class,
                authentication.getPrincipal()
        );
    }

    private Jwt jwt(
            String subject,
            Map<String, Object> claims
    ) {
        Jwt.Builder builder =
                Jwt.withTokenValue(
                                "raw-secret-token"
                        )
                        .header(
                                "alg",
                                "HS256"
                        )
                        .issuedAt(NOW)
                        .expiresAt(
                                NOW.plusSeconds(
                                        300
                                )
                        );

        if (subject != null) {
            builder.subject(subject);
        }

        claims.forEach(
                builder::claim
        );

        return builder.build();
    }
}
