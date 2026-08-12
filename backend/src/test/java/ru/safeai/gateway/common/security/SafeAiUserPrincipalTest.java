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

    private static final String EMAIL =
            "user@example.com";

    private static final String PASSWORD_HASH =
            "{bcrypt}$2a$12$abcdefghijklmnopqrstuuuuuuuuuuuuuuuuuuuuuuuuuu";

    @Test
    void passwordPrincipalContainsExpectedSecurityState() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal
                        .passwordPrincipal(
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
                                        ),
                                        authority(
                                                "ROLE_ADMIN"
                                        )
                                )
                        );

        assertThat(principal.getId())
                .isEqualTo(USER_ID);

        assertThat(
                principal.getOrganizationId()
        ).isEqualTo(ORGANIZATION_ID);

        assertThat(principal.getEmail())
                .isEqualTo(EMAIL);

        assertThat(principal.getUsername())
                .isEqualTo(EMAIL);

        assertThat(principal.getPassword())
                .isEqualTo(PASSWORD_HASH);

        assertThat(principal.isEnabled())
                .isTrue();

        assertThat(principal.getTokenVersion())
                .isEqualTo(3L);

        assertThat(
                principal.getOrganizationAuthVersion()
        ).isEqualTo(9L);

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_ADMIN",
                "ROLE_USER"
        );
    }

    @Test
    void accessTokenPrincipalNeverContainsPassword() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal
                        .accessTokenPrincipal(
                                USER_ID,
                                ORGANIZATION_ID,
                                EMAIL,
                                3L,
                                9L,
                                List.of(
                                        authority(
                                                "ROLE_USER"
                                        )
                                )
                        );

        assertThat(
                principal.getPassword()
        ).isNull();

        assertThat(
                principal.isEnabled()
        ).isTrue();
    }

    @Test
    void eraseCredentialsRemovesPasswordHash() {
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        principal.eraseCredentials();

        assertThat(
                principal.getPassword()
        ).isNull();
    }

    @Test
    void authoritiesAreCanonicalSortedAndImmutable() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal
                        .accessTokenPrincipal(
                                USER_ID,
                                ORGANIZATION_ID,
                                EMAIL,
                                1L,
                                2L,
                                List.of(
                                        authority(
                                                "user"
                                        ),
                                        authority(
                                                "ROLE_SUPER_ADMIN"
                                        ),
                                        authority(
                                                "admin"
                                        ),
                                        authority(
                                                "ROLE_USER"
                                        )
                                )
                        );

        assertThat(
                principal.authorityNames()
        ).containsExactly(
                "ROLE_ADMIN",
                "ROLE_SUPER_ADMIN",
                "ROLE_USER"
        );

        assertThatThrownBy(() ->
                clearAuthorities(
                        principal.getAuthorities()
                )
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

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
                        ORGANIZATION_ID
                                .toString()
                )
                .contains(
                        "organizationAuthVersion=9"
                )
                .doesNotContain(EMAIL)
                .doesNotContain(
                        PASSWORD_HASH
                )
                .doesNotContain(
                        "eyJ"
                );
    }

    @Test
    void principalsWithSameIdRemainDistinctInstances() {
        SafeAiUserPrincipal first =
                SafeAiUserPrincipal
                        .accessTokenPrincipal(
                                USER_ID,
                                ORGANIZATION_ID,
                                EMAIL,
                                3L,
                                9L,
                                List.of(
                                        authority(
                                                "ROLE_USER"
                                        )
                                )
                        );

        SafeAiUserPrincipal second =
                SafeAiUserPrincipal
                        .accessTokenPrincipal(
                                USER_ID,
                                UUID.randomUUID(),
                                EMAIL,
                                99L,
                                100L,
                                List.of(
                                        authority(
                                                "ROLE_ADMIN"
                                        )
                                )
                        );

        assertThat(first)
                .isNotSameAs(second)
                .isNotEqualTo(second);
    }

    @Test
    void negativeTokenVersionIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal
                        .accessTokenPrincipal(
                                USER_ID,
                                ORGANIZATION_ID,
                                EMAIL,
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
                SafeAiUserPrincipal
                        .accessTokenPrincipal(
                                USER_ID,
                                ORGANIZATION_ID,
                                EMAIL,
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
        ).doesNotContain(7);
    }

    @Test
    void legacyAccessTokenPrincipalFactoryWithoutOrganizationVersionDoesNotExist() {
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
                                Method::getParameterCount
                        )
        ).doesNotContain(5);
    }

    private SafeAiUserPrincipal
    passwordPrincipal() {
        return SafeAiUserPrincipal
                .passwordPrincipal(
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
