package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeAiUserPrincipalTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    private static final UUID OTHER_ORGANIZATION_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final String EMAIL =
            "user@example.com";

    private static final String PASSWORD_HASH =
            "{bcrypt}$2a$12$abcdefghijklmnopqrstuuuuuuuuuuuuuuuuuuuuuu";

    private static final String EXACTLY_ONE_ROLE_MESSAGE =
            "Должна быть ровно одна системная роль";

    /*
     * ============================================================
     * Password principal
     * ============================================================
     */

    @Test
    void passwordPrincipalContainsExpectedSecurityState() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        3L,
                        9L,
                        List.of(
                                authority(
                                        "role_user"
                                )
                        )
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
                principal.getEmail()
        ).isEqualTo(
                EMAIL
        );

        assertThat(
                principal.getUsername()
        ).isEqualTo(
                EMAIL
        );

        assertThat(
                principal.getPassword()
        ).isEqualTo(
                PASSWORD_HASH
        );

        assertThat(
                principal.isEnabled()
        ).isTrue();

        assertThat(
                principal.getTokenVersion()
        ).isEqualTo(
                3L
        );

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(
                9L
        );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_USER"
        );
    }

    @Test
    void passwordPrincipalCanonicalizesSingleRoleAlias() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        3L,
                        9L,
                        List.of(
                                authority(
                                        "admin"
                                )
                        )
                );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_ADMIN"
        );
    }

    /*
     * ============================================================
     * Access-token principal
     * ============================================================
     */

    @Test
    void accessTokenPrincipalContainsNoEmailOrPassword() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        3L,
                        9L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                )
                        )
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

        assertThat(
                principal.isEnabled()
        ).isTrue();

        assertThat(
                principal.getTokenVersion()
        ).isEqualTo(
                3L
        );

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(
                9L
        );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_USER"
        );
    }

    @Test
    void accessTokenPrincipalCanonicalizesLowercaseRole() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        2L,
                        List.of(
                                authority(
                                        "super_admin"
                                )
                        )
                );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_SUPER_ADMIN"
        );
    }

    /*
     * ============================================================
     * Exactly-one-role invariant
     * ============================================================
     */

    @Test
    void passwordPrincipalRejectsMultipleDifferentRoles() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        3L,
                        9L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                ),
                                authority(
                                        "ROLE_ADMIN"
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        EXACTLY_ONE_ROLE_MESSAGE
                );
    }

    @Test
    void accessTokenPrincipalRejectsMultipleDifferentRoles() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        2L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                ),
                                authority(
                                        "ROLE_SUPER_ADMIN"
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        EXACTLY_ONE_ROLE_MESSAGE
                );
    }

    @Test
    void accessTokenPrincipalRejectsMultipleAliasesOfSameRole() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        2L,
                        List.of(
                                authority(
                                        "USER"
                                ),
                                authority(
                                        "role_user"
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        EXACTLY_ONE_ROLE_MESSAGE
                );
    }

    @Test
    void emptyAuthoritiesAreRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        2L,
                        List.of()
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        EXACTLY_ONE_ROLE_MESSAGE
                );
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullAuthoritiesAreRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        2L,
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "authorities не должен быть null"
                );
    }

    @Test
    void unknownAuthorityIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        2L,
                        List.of(
                                authority(
                                        "ROLE_ROOT"
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                );
    }

    /*
     * ============================================================
     * Authorities collection
     * ============================================================
     */

    @Test
    void authoritiesAreCanonicalAndImmutable() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        2L,
                        List.of(
                                authority(
                                        "role_user"
                                )
                        )
                );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_USER"
        );

        assertThat(
                principal.getAuthorities()
        ).hasSize(
                1
        );

        assertThatThrownBy(() ->
                clearAuthorities(
                        principal.getAuthorities()
                )
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

    /*
     * ============================================================
     * Credentials
     * ============================================================
     */

    @Test
    void eraseCredentialsRemovesPasswordHash() {
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        assertThat(
                principal.getPassword()
        ).isEqualTo(
                PASSWORD_HASH
        );

        principal.eraseCredentials();

        assertThat(
                principal.getPassword()
        ).isNull();
    }

    /*
     * ============================================================
     * Logging / sensitive data
     * ============================================================
     */

    @Test
    void toStringDoesNotExposeEmailPasswordOrJwtLikeCredentials() {
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        String text =
                principal.toString();

        assertThat(text)
                .contains(
                        USER_ID.toString()
                )
                .contains(
                        ORGANIZATION_ID.toString()
                )
                .contains(
                        "organizationAuthVersion=9"
                )
                .doesNotContain(
                        EMAIL
                )
                .doesNotContain(
                        PASSWORD_HASH
                )
                .doesNotContain(
                        "eyJ"
                );
    }

    /*
     * ============================================================
     * Identity semantics
     * ============================================================
     */

    @Test
    void principalsWithSameIdRemainDistinctInstances() {
        SafeAiUserPrincipal first =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        3L,
                        9L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                )
                        )
                );

        SafeAiUserPrincipal second =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        OTHER_ORGANIZATION_ID,
                        99L,
                        100L,
                        List.of(
                                authority(
                                        "ROLE_ADMIN"
                                )
                        )
                );

        assertThat(first)
                .isNotSameAs(
                        second
                )
                .isNotEqualTo(
                        second
                );
    }

    /*
     * ============================================================
     * Version validation
     * ============================================================
     */

    @Test
    void negativeTokenVersionIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        -1L,
                        0L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "tokenVersion не может быть отрицательным"
                );
    }

    @Test
    void negativeOrganizationAuthVersionIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        -1L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                )
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "organizationAuthVersion не может быть отрицательным"
                );
    }

    @Test
    void zeroVersionsAreAccepted() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                )
                        )
                );

        assertThat(
                principal.getTokenVersion()
        ).isZero();

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isZero();
    }

    @Test
    void maximumVersionsAreAccepted() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                )
                        )
                );

        assertThat(
                principal.getTokenVersion()
        ).isEqualTo(
                Long.MAX_VALUE
        );

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(
                Long.MAX_VALUE
        );
    }

    /*
     * ============================================================
     * Null identity validation
     * ============================================================
     */

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullUserIdIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        null,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                )
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "id не должен быть null"
                );
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullOrganizationIdIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        null,
                        0L,
                        0L,
                        List.of(
                                authority(
                                        "ROLE_USER"
                                )
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessageContaining(
                        "organizationId"
                );
    }

    /*
     * ============================================================
     * Factory API contract
     * ============================================================
     */

    @Test
    void passwordPrincipalUsesCurrentEightArgumentContract() {
        assertThat(
                Arrays.stream(
                                SafeAiUserPrincipal.class
                                        .getDeclaredMethods()
                        )
                        .filter(method ->
                                method.getName()
                                        .equals(
                                                "passwordPrincipal"
                                        )
                        )
                        .map(
                                Method::getParameterTypes
                        )
        ).anyMatch(parameterTypes ->
                Arrays.equals(
                        parameterTypes,
                        new Class<?>[]{
                                UUID.class,
                                UUID.class,
                                String.class,
                                String.class,
                                boolean.class,
                                long.class,
                                long.class,
                                Collection.class
                        }
                )
        );
    }

    @Test
    void legacyPasswordPrincipalFactoryWithoutOrganizationVersionDoesNotExist() {
        assertThat(
                Arrays.stream(
                                SafeAiUserPrincipal.class
                                        .getDeclaredMethods()
                        )
                        .filter(method ->
                                method.getName()
                                        .equals(
                                                "passwordPrincipal"
                                        )
                        )
                        .map(
                                Method::getParameterCount
                        )
        ).doesNotContain(
                7
        );
    }

    @Test
    void accessTokenPrincipalUsesCurrentFiveArgumentContract() {
        assertThat(
                Arrays.stream(
                                SafeAiUserPrincipal.class
                                        .getDeclaredMethods()
                        )
                        .filter(method ->
                                method.getName()
                                        .equals(
                                                "accessTokenPrincipal"
                                        )
                        )
                        .map(
                                Method::getParameterTypes
                        )
        ).anyMatch(parameterTypes ->
                Arrays.equals(
                        parameterTypes,
                        new Class<?>[]{
                                UUID.class,
                                UUID.class,
                                long.class,
                                long.class,
                                Collection.class
                        }
                )
        );
    }

    @Test
    void legacyAccessTokenPrincipalFactoryWithEmailDoesNotExist() {
        assertThat(
                Arrays.stream(
                                SafeAiUserPrincipal.class
                                        .getDeclaredMethods()
                        )
                        .filter(method ->
                                method.getName()
                                        .equals(
                                                "accessTokenPrincipal"
                                        )
                        )
                        .map(
                                Method::getParameterTypes
                        )
        ).noneMatch(parameterTypes ->
                Arrays.asList(
                        parameterTypes
                ).contains(
                        String.class
                )
        );
    }

    /*
     * ============================================================
     * Helpers
     * ============================================================
     */

    private SafeAiUserPrincipal passwordPrincipal() {
        return SafeAiUserPrincipal.passwordPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                EMAIL,
                PASSWORD_HASH,
                true,
                3L,
                9L,
                List.of(
                        authority(
                                "ROLE_USER"
                        )
                )
        );
    }

    private static void clearAuthorities(
            Collection<? extends GrantedAuthority> authorities
    ) {
        authorities.clear();
    }

    private static GrantedAuthority authority(
            String value
    ) {
        return new SimpleGrantedAuthority(
                value
        );
    }
}