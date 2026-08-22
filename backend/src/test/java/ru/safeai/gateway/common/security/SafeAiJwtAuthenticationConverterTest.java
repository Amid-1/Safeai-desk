package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Unit tests for the strict SafeAI access-token identity contract.
 *
 * <p>Этот тест проверяет только responsibilities
 * {@link SafeAiJwtAuthenticationConverter}:
 *
 * <ul>
 *     <li>security identity claims;</li>
 *     <li>jti/iat presence;</li>
 *     <li>UUID claims;</li>
 *     <li>security epoch/version claims;</li>
 *     <li>exactly-one-role invariant;</li>
 *     <li>role canonicalization;</li>
 *     <li>principal construction;</li>
 *     <li>absence of credentials/PII/raw JWT in principal;</li>
 *     <li>Resource Server exception translation.</li>
 * </ul>
 *
 * <p>Signature, issuer, audience, expiration, JOSE {@code typ},
 * algorithm and {@code kid} belong to JwtDecoder/resource-server
 * integration tests and intentionally are not re-tested here.</p>
 */
class SafeAiJwtAuthenticationConverterTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID TEST_JTI =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-12T12:00:00Z"
            );

    private static final String TEST_KEY_ID =
            "safeai-test-active";

    private static final String RAW_TOKEN =
            "raw-secret-token";

    private static final String EXACTLY_ONE_ROLE_MESSAGE =
            "JWT must contain exactly one role";

    private static final String INVALID_ROLES_MESSAGE =
            "JWT roles contain invalid values";

    private final SafeAiJwtAuthenticationConverter converter =
            new SafeAiJwtAuthenticationConverter();

    /*
     * ------------------------------------------------------------
     * Valid identity
     * ------------------------------------------------------------
     */

    @Test
    void validJwtCreatesAuthenticatedPrincipalWithoutCredentialsOrPii() {
        AbstractAuthenticationToken authentication =
                convert(
                        USER_ID.toString(),
                        validClaims()
                );

        assertThat(authentication)
                .isInstanceOf(
                        UsernamePasswordAuthenticationToken.class
                );

        assertThat(
                authentication.isAuthenticated()
        ).isTrue();

        assertThat(
                authentication.getCredentials()
        ).isNull();

        SafeAiUserPrincipal principal =
                assertInstanceOf(
                        SafeAiUserPrincipal.class,
                        authentication.getPrincipal()
                );

        assertThat(
                principal.getId()
        ).isEqualTo(
                USER_ID
        );

        assertThat(
                principal.getOrganizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                principal.getTokenVersion()
        ).isEqualTo(
                4L
        );

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(
                8L
        );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_USER"
        );

        assertThat(
                principal.getEmail()
        ).isNull();

        assertThat(
                principal.getPassword()
        ).isNull();

        assertThat(
                principal.getUsername()
        ).isEqualTo(
                USER_ID.toString()
        );
    }

    @Test
    void validJwtDoesNotExposeRawTokenThroughAuthentication() {
        AbstractAuthenticationToken authentication =
                convert(
                        USER_ID.toString(),
                        validClaims()
                );

        assertThat(
                authentication.getCredentials()
        ).isNull();

        assertThat(
                authentication.toString()
        ).doesNotContain(
                RAW_TOKEN
        );
    }

    @Test
    void rawJwtIsNotExposedByPrincipalToString() {
        SafeAiUserPrincipal principal =
                convertPrincipal(
                        USER_ID.toString(),
                        validClaims()
                );

        assertThat(
                principal.toString()
        ).doesNotContain(
                RAW_TOKEN
        );
    }

    @Test
    void legacyEmailClaimIsIgnoredAndNotPropagatedToPrincipal() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "email",
                "private-user@example.test"
        );

        SafeAiUserPrincipal principal =
                convertPrincipal(
                        USER_ID.toString(),
                        claims
                );

        assertThat(
                principal.getEmail()
        ).isNull();

        assertThat(
                principal.toString()
        )
                .doesNotContain(
                        "private-user@example.test"
                );
    }

    @Test
    void unrelatedAdditionalClaimDoesNotAffectIdentity() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "unrelatedClaim",
                "ignored-value"
        );

        SafeAiUserPrincipal principal =
                convertPrincipal(
                        USER_ID.toString(),
                        claims
                );

        assertThat(
                principal.getId()
        ).isEqualTo(
                USER_ID
        );

        assertThat(
                principal.getOrganizationId()
        ).isEqualTo(
                ORGANIZATION_ID
        );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_USER"
        );
    }

    /*
     * ------------------------------------------------------------
     * Subject
     * ------------------------------------------------------------
     */

    @Test
    void missingSubjectIsRejected() {
        assertRejected(
                jwt(
                        null,
                        validClaims()
                ),
                "subject"
        );
    }

    @Test
    void blankSubjectIsRejected() {
        assertRejected(
                jwt(
                        "   ",
                        validClaims()
                ),
                "subject"
        );
    }

    @Test
    void subjectDifferentFromUserIdIsRejected() {
        assertRejected(
                UUID.randomUUID()
                        .toString(),
                validClaims(),
                "subject does not match userId"
        );
    }

    /*
     * ------------------------------------------------------------
     * jti
     * ------------------------------------------------------------
     */

    @Test
    void missingJtiIsRejected() {
        assertRejected(
                jwtWithoutJti(
                        USER_ID.toString(),
                        validClaims()
                ),
                "jti"
        );
    }

    @Test
    void blankJtiIsRejected() {
        assertRejected(
                jwtWithJti(
                        USER_ID.toString(),
                        validClaims(),
                        "   "
                ),
                "jti"
        );
    }

    @Test
    void malformedJtiUuidIsRejected() {
        assertRejected(
                jwtWithJti(
                        USER_ID.toString(),
                        validClaims(),
                        "not-a-uuid"
                ),
                "jti"
        );
    }

    @Test
    void validUuidJtiIsAccepted() {
        SafeAiUserPrincipal principal =
                convertPrincipal(
                        jwtWithJti(
                                USER_ID.toString(),
                                validClaims(),
                                TEST_JTI.toString()
                        )
                );

        assertThat(
                principal.getId()
        ).isEqualTo(
                USER_ID
        );
    }

    /*
     * ------------------------------------------------------------
     * iat
     * ------------------------------------------------------------
     */

    @Test
    void missingIssuedAtIsRejected() {
        assertRejected(
                jwtWithoutIssuedAt(
                        USER_ID.toString(),
                        validClaims()
                ),
                "iat"
        );
    }

    /*
     * ------------------------------------------------------------
     * userId
     * ------------------------------------------------------------
     */

    @Test
    void missingUserIdIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.remove(
                "userId"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "userId"
        );
    }

    @Test
    void blankUserIdIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "userId",
                "   "
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "userId"
        );
    }

    @Test
    void nonStringUserIdIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "userId",
                123
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "userId"
        );
    }

    @Test
    void malformedUserUuidIsRejected() {
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

    /*
     * ------------------------------------------------------------
     * organizationId
     * ------------------------------------------------------------
     */

    @Test
    void missingOrganizationIdIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.remove(
                "organizationId"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "organizationId"
        );
    }

    @Test
    void blankOrganizationIdIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "organizationId",
                "   "
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "organizationId"
        );
    }

    @Test
    void nonStringOrganizationIdIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "organizationId",
                123
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "organizationId"
        );
    }

    @Test
    void malformedOrganizationUuidIsRejected() {
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

    /*
     * ------------------------------------------------------------
     * tokenVersion
     * ------------------------------------------------------------
     */

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
    void nonNumericTokenVersionIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "tokenVersion",
                "4"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "not numeric"
        );
    }

    @Test
    void negativeTokenVersionIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "tokenVersion",
                -1L
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "negative"
        );
    }

    @Test
    void invalidIntegralTokenVersionRepresentationsAreRejected() {
        assertInvalidIntegralValues(
                "tokenVersion"
        );
    }

    @Test
    void supportedIntegralTokenVersionRepresentationsAreAccepted() {
        assertSupportedIntegralValues(
                "tokenVersion"
        );
    }

    /*
     * ------------------------------------------------------------
     * organizationAuthVersion
     * ------------------------------------------------------------
     */

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
    void negativeOrganizationAuthVersionIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "organizationAuthVersion",
                -1L
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                "negative"
        );
    }

    @Test
    void invalidIntegralOrganizationAuthVersionRepresentationsAreRejected() {
        assertInvalidIntegralValues(
                "organizationAuthVersion"
        );
    }

    @Test
    void supportedIntegralOrganizationAuthVersionRepresentationsAreAccepted() {
        assertSupportedIntegralValues(
                "organizationAuthVersion"
        );
    }

    /*
     * ------------------------------------------------------------
     * roles
     * ------------------------------------------------------------
     */

    @Test
    void missingRolesAreRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.remove(
                "roles"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                EXACTLY_ONE_ROLE_MESSAGE
        );
    }

    @Test
    void rolesWithWrongContainerTypeAreRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                "USER"
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                EXACTLY_ONE_ROLE_MESSAGE
        );
    }

    @Test
    void emptyRolesAreRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of()
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                EXACTLY_ONE_ROLE_MESSAGE
        );
    }

    @Test
    void multipleDifferentRolesAreRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of(
                        "USER",
                        "ADMIN"
                )
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                EXACTLY_ONE_ROLE_MESSAGE
        );
    }

    @Test
    void multipleAliasesOfSameRoleAreRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of(
                        "USER",
                        "role_user"
                )
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                EXACTLY_ONE_ROLE_MESSAGE
        );
    }

    @Test
    void nonStringRoleIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of(
                        123
                )
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                INVALID_ROLES_MESSAGE
        );
    }

    @Test
    void nullRoleIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                Collections.singletonList(
                        null
                )
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                INVALID_ROLES_MESSAGE
        );
    }

    @Test
    void blankRoleIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of(
                        "   "
                )
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                INVALID_ROLES_MESSAGE
        );
    }

    @Test
    void unknownRoleIsRejected() {
        Map<String, Object> claims =
                validClaims();

        claims.put(
                "roles",
                List.of(
                        "ROOT"
                )
        );

        assertRejected(
                USER_ID.toString(),
                claims,
                INVALID_ROLES_MESSAGE
        );
    }

    @Test
    void supportedRoleRepresentationsAreCanonicalized() {
        List<RoleCase> cases =
                List.of(
                        new RoleCase(
                                "USER",
                                "ROLE_USER"
                        ),
                        new RoleCase(
                                "user",
                                "ROLE_USER"
                        ),
                        new RoleCase(
                                "ROLE_USER",
                                "ROLE_USER"
                        ),
                        new RoleCase(
                                "role_user",
                                "ROLE_USER"
                        ),
                        new RoleCase(
                                "ADMIN",
                                "ROLE_ADMIN"
                        ),
                        new RoleCase(
                                "admin",
                                "ROLE_ADMIN"
                        ),
                        new RoleCase(
                                "ROLE_ADMIN",
                                "ROLE_ADMIN"
                        ),
                        new RoleCase(
                                "SUPER_ADMIN",
                                "ROLE_SUPER_ADMIN"
                        ),
                        new RoleCase(
                                "ROLE_SUPER_ADMIN",
                                "ROLE_SUPER_ADMIN"
                        )
                );

        for (RoleCase testCase : cases) {
            Map<String, Object> claims =
                    validClaims();

            claims.put(
                    "roles",
                    List.of(
                            testCase.rawRole()
                    )
            );

            SafeAiUserPrincipal principal =
                    convertPrincipal(
                            USER_ID.toString(),
                            claims
                    );

            assertThat(
                    principal.authorityNames()
            )
                    .as(
                            "raw role: %s",
                            testCase.rawRole()
                    )
                    .containsExactly(
                            testCase.expectedAuthority()
                    );
        }
    }

    /*
     * ------------------------------------------------------------
     * Resource Server adapter
     * ------------------------------------------------------------
     */

    @Test
    void convertForResourceServerAcceptsValidJwt() {
        AbstractAuthenticationToken authentication =
                Objects.requireNonNull(
                        converter.convertForResourceServer(
                                jwt(
                                        USER_ID.toString(),
                                        validClaims()
                                )
                        ),
                        "converter returned null authentication"
                );

        assertThat(
                authentication.isAuthenticated()
        ).isTrue();

        SafeAiUserPrincipal principal =
                assertInstanceOf(
                        SafeAiUserPrincipal.class,
                        authentication.getPrincipal()
                );

        assertThat(
                principal.getId()
        ).isEqualTo(
                USER_ID
        );
    }

    @Test
    void convertForResourceServerTranslatesBadJwtToInvalidBearerToken() {
        Jwt malformedJwt =
                jwtWithoutJti(
                        USER_ID.toString(),
                        validClaims()
                );

        assertThatThrownBy(() ->
                converter.convertForResourceServer(
                        malformedJwt
                )
        )
                .isInstanceOf(
                        InvalidBearerTokenException.class
                )
                .hasMessage(
                        "Invalid access token"
                )
                .hasCauseInstanceOf(
                        BadJwtException.class
                );
    }

    /*
     * ------------------------------------------------------------
     * Null API contract
     * ------------------------------------------------------------
     */

    @SuppressWarnings("DataFlowIssue")
    @Test
    void nullJwtIsRejected() {
        assertThatThrownBy(() ->
                converter.convert(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "jwt не должен быть null"
                );
    }

    /*
     * ------------------------------------------------------------
     * Numeric claim helpers
     * ------------------------------------------------------------
     */

    private void assertSupportedIntegralValues(
            String claimName
    ) {
        List<Number> supported =
                List.of(
                        (byte) 0,
                        (short) 1,
                        2,
                        3L,
                        BigInteger.valueOf(4L),
                        new BigDecimal("5"),
                        6.0D,
                        7.0F,
                        Long.MAX_VALUE
                );

        for (Number value : supported) {
            Map<String, Object> claims =
                    validClaims();

            claims.put(
                    claimName,
                    value
            );

            SafeAiUserPrincipal principal =
                    convertPrincipal(
                            USER_ID.toString(),
                            claims
                    );

            long actual =
                    switch (claimName) {
                        case "tokenVersion" ->
                                principal.getTokenVersion();

                        case "organizationAuthVersion" ->
                                principal.getOrganizationAuthVersion();

                        default ->
                                throw new AssertionError(
                                        "Unexpected claim: "
                                                + claimName
                                );
                    };

            assertThat(actual)
                    .as(
                            "%s represented by %s",
                            claimName,
                            value.getClass()
                                    .getSimpleName()
                    )
                    .isEqualTo(
                            value.longValue()
                    );
        }
    }

    private void assertInvalidIntegralValues(
            String claimName
    ) {
        List<Number> invalid =
                List.of(
                        1.5D,
                        1.5F,
                        new BigDecimal("1.5"),

                        BigInteger
                                .valueOf(
                                        Long.MAX_VALUE
                                )
                                .add(
                                        BigInteger.ONE
                                ),

                        BigInteger
                                .valueOf(
                                        Long.MIN_VALUE
                                )
                                .subtract(
                                        BigInteger.ONE
                                ),

                        new BigDecimal(
                                Long.MAX_VALUE
                        ).add(
                                BigDecimal.ONE
                        ),

                        Double.NaN,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,

                        Float.NaN,
                        Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY
                );

        for (Number value : invalid) {
            Map<String, Object> claims =
                    validClaims();

            claims.put(
                    claimName,
                    value
            );

            assertThatThrownBy(() ->
                    converter.convert(
                            jwt(
                                    USER_ID.toString(),
                                    claims
                            )
                    )
            )
                    .as(
                            "%s represented by %s: %s",
                            claimName,
                            value.getClass()
                                    .getSimpleName(),
                            value
                    )
                    .isInstanceOf(
                            BadJwtException.class
                    )
                    .hasMessageContaining(
                            "integral long"
                    );
        }
    }

    /*
     * ------------------------------------------------------------
     * Assertions
     * ------------------------------------------------------------
     */

    private void assertRejected(
            String subject,
            Map<String, Object> claims,
            String expectedMessage
    ) {
        assertRejected(
                jwt(
                        subject,
                        claims
                ),
                expectedMessage
        );
    }

    private void assertRejected(
            Jwt jwt,
            String expectedMessage
    ) {
        assertThatThrownBy(() ->
                converter.convert(
                        jwt
                )
        )
                .isInstanceOf(
                        BadJwtException.class
                )
                .hasMessageContaining(
                        expectedMessage
                );
    }

    /*
     * ------------------------------------------------------------
     * Valid baseline
     * ------------------------------------------------------------
     */

    private Map<String, Object> validClaims() {
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
                "roles",
                List.of(
                        "USER"
                )
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

    /*
     * ------------------------------------------------------------
     * Conversion helpers
     * ------------------------------------------------------------
     */

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
        return convertPrincipal(
                jwt(
                        subject,
                        claims
                )
        );
    }

    private SafeAiUserPrincipal convertPrincipal(
            Jwt jwt
    ) {
        AbstractAuthenticationToken authentication =
                Objects.requireNonNull(
                        converter.convert(
                                jwt
                        ),
                        "converter returned null authentication"
                );

        return assertInstanceOf(
                SafeAiUserPrincipal.class,
                authentication.getPrincipal()
        );
    }

    /*
     * ------------------------------------------------------------
     * JWT factories
     * ------------------------------------------------------------
     */

    /**
     * Полностью валидный baseline Jwt для unit test.
     *
     * <p>Каждый negative test должен изменять только ту часть
     * контракта, которую он непосредственно проверяет.</p>
     */
    private Jwt jwt(
            String subject,
            Map<String, Object> claims
    ) {
        Jwt.Builder builder =
                baseJwtBuilder()
                        .jti(
                                TEST_JTI.toString()
                        )
                        .issuedAt(
                                NOW
                        );

        return buildJwt(
                builder,
                subject,
                claims
        );
    }

    private Jwt jwtWithJti(
            String subject,
            Map<String, Object> claims,
            String jti
    ) {
        Jwt.Builder builder =
                baseJwtBuilder()
                        .jti(
                                jti
                        )
                        .issuedAt(
                                NOW
                        );

        return buildJwt(
                builder,
                subject,
                claims
        );
    }

    private Jwt jwtWithoutJti(
            String subject,
            Map<String, Object> claims
    ) {
        Jwt.Builder builder =
                baseJwtBuilder()
                        .issuedAt(
                                NOW
                        );

        return buildJwt(
                builder,
                subject,
                claims
        );
    }

    private Jwt jwtWithoutIssuedAt(
            String subject,
            Map<String, Object> claims
    ) {
        Jwt.Builder builder =
                baseJwtBuilder()
                        .jti(
                                TEST_JTI.toString()
                        );

        return buildJwt(
                builder,
                subject,
                claims
        );
    }

    private Jwt buildJwt(
            Jwt.Builder builder,
            String subject,
            Map<String, Object> claims
    ) {
        if (subject != null) {
            builder.subject(
                    subject
            );
        }

        claims.forEach(
                builder::claim
        );

        return builder.build();
    }

    private Jwt.Builder baseJwtBuilder() {
        return Jwt.withTokenValue(
                        RAW_TOKEN
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
                        TEST_KEY_ID
                )
                .expiresAt(
                        NOW.plusSeconds(
                                300
                        )
                );
    }

    private record RoleCase(
            String rawRole,
            String expectedAuthority
    ) {
    }
}