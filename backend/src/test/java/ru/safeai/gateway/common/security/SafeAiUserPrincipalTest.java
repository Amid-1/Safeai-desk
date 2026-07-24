package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Method;
import java.util.ArrayList;
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

    private static final UUID OTHER_USER_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
            );

    private static final String EMAIL =
            "user@example.com";

    private static final String PASSWORD_HASH =
            "{bcrypt}$2a$10$test-password-hash";

    @Test
    void passwordPrincipalContainsExpectedUserDetails() {
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        assertThat(principal.getId())
                .isEqualTo(USER_ID);

        assertThat(principal.getOrganizationId())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(principal.getEmail())
                .isEqualTo(EMAIL);

        assertThat(principal.getUsername())
                .isEqualTo(EMAIL);

        assertThat(principal.getPassword())
                .isEqualTo(PASSWORD_HASH);

        assertThat(principal.getTokenVersion())
                .isEqualTo(3L);

        assertThat(principal.isEnabled())
                .isTrue();

        assertThat(principal.isAccountNonExpired())
                .isTrue();

        assertThat(principal.isAccountNonLocked())
                .isTrue();

        assertThat(principal.isCredentialsNonExpired())
                .isTrue();

        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void disabledPasswordPrincipalRemainsDisabled() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        false,
                        3L,
                        List.of(
                                authority("ROLE_USER")
                        )
                );

        assertThat(principal.isEnabled())
                .isFalse();
    }

    @Test
    void authoritiesAreCanonicalizedDeduplicatedAndSorted() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        3L,
                        List.of(
                                authority("ROLE_USER"),
                                authority("admin"),
                                authority("USER"),
                                authority("role_admin")
                        )
                );

        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(
                        "ROLE_ADMIN",
                        "ROLE_USER"
                );
    }

    @Test
    void authoritiesAreDefensivelyCopied() {
        List<GrantedAuthority> source =
                new ArrayList<>();

        source.add(
                authority("ROLE_USER")
        );

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        3L,
                        source
                );

        source.clear();
        source.add(
                authority("ROLE_ADMIN")
        );

        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void authoritiesAreImmutable() {
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        assertThat(principal.getAuthorities())
                .isUnmodifiable();
    }

    @Test
    void authorityNamesAreCanonicalAndImmutable() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        3L,
                        List.of(
                                authority("admin"),
                                authority("ROLE_USER")
                        )
                );

        assertThat(principal.authorityNames())
                .containsExactly(
                        "ROLE_ADMIN",
                        "ROLE_USER"
                )
                .isUnmodifiable();
    }

    @Test
    void eraseCredentialsRemovesPasswordHash() {
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        assertThat(principal.getPassword())
                .isEqualTo(PASSWORD_HASH);

        principal.eraseCredentials();

        assertThat(principal.getPassword())
                .isEmpty();
    }

    @Test
    void eraseCredentialsIsIdempotent() {
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        principal.eraseCredentials();
        principal.eraseCredentials();

        assertThat(principal.getPassword())
                .isEmpty();
    }

    @Test
    void accessTokenPrincipalNeverContainsPassword() {
        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.accessTokenPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        3L,
                        List.of(
                                authority("ROLE_USER")
                        )
                );

        assertThat(principal.getId())
                .isEqualTo(USER_ID);

        assertThat(principal.getOrganizationId())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(principal.getEmail())
                .isEqualTo(EMAIL);

        assertThat(principal.getUsername())
                .isEqualTo(EMAIL);

        assertThat(principal.getPassword())
                .isEmpty();

        assertThat(principal.isEnabled())
                .isTrue();

        assertThat(principal.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void classDoesNotExposePasswordHashGetter() {
        assertThat(
                SafeAiUserPrincipal.class.getMethods()
        )
                .extracting(Method::getName)
                .doesNotContain("getPasswordHash");
    }

    @Test
    void constructorIsNotPartOfPublicApi() {
        assertThat(
                SafeAiUserPrincipal.class.getConstructors()
        ).isEmpty();
    }

    @Test
    void toStringDoesNotExposeEmailOrPasswordHash() {
        SafeAiUserPrincipal principal =
                passwordPrincipal();

        String result = principal.toString();

        assertThat(result)
                .contains(
                        USER_ID.toString(),
                        ORGANIZATION_ID.toString(),
                        "enabled=true",
                        "tokenVersion=3",
                        "ROLE_USER"
                )
                .doesNotContain(
                        EMAIL,
                        PASSWORD_HASH,
                        "passwordHash"
                );
    }

    @Test
    void principalsWithSameIdRemainDistinctInstances() {
        SafeAiUserPrincipal first =
                passwordPrincipal();

        SafeAiUserPrincipal second =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        UUID.randomUUID(),
                        "other@example.com",
                        "{bcrypt}other-hash",
                        false,
                        99L,
                        List.of(
                                authority("ROLE_ADMIN")
                        )
                );

        assertThat(first)
                .isNotSameAs(second)
                .isNotEqualTo(second);
    }

    @Test
    void principalsWithDifferentIdsAreNotEqual() {
        SafeAiUserPrincipal first =
                passwordPrincipal();

        SafeAiUserPrincipal second =
                SafeAiUserPrincipal.passwordPrincipal(
                        OTHER_USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        3L,
                        List.of(
                                authority("ROLE_USER")
                        )
                );

        assertThat(first)
                .isNotEqualTo(second);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullIdIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        null,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_USER")
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
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        null,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_USER")
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "organizationId не должен быть null"
                );
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullEmailIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        null,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_USER")
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "email не должен быть null"
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "\t",
            "User@example.com",
            " user@example.com",
            "user@example.com "
    })
    void nonCanonicalEmailIsRejected(
            String email
    ) {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        email,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_USER")
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "canonical lowercase email"
                );
    }

    @Test
    void emailWithExactly255CharactersIsAccepted() {
        String email =
                "a".repeat(243)
                        + "@example.com";

        assertThat(email)
                .hasSize(255);

        SafeAiUserPrincipal principal =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        email,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_USER")
                        )
                );

        assertThat(principal.getEmail())
                .isEqualTo(email);
    }

    @Test
    void emailLongerThan255CharactersIsRejected() {
        String email =
                "a".repeat(244)
                        + "@example.com";

        assertThat(email)
                .hasSize(256);

        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        email,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_USER")
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "canonical lowercase email"
                );
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullPasswordHashIsRejectedForPasswordAuthentication() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        null,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_USER")
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "passwordHash не должен быть null"
                );
    }

    @Test
    void negativeTokenVersionIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        -1L,
                        List.of(
                                authority("ROLE_USER")
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
    void zeroAndMaximumTokenVersionsAreAccepted() {
        SafeAiUserPrincipal zeroVersion =
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_USER")
                        )
                );

        SafeAiUserPrincipal maximumVersion =
                SafeAiUserPrincipal.passwordPrincipal(
                        OTHER_USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        Long.MAX_VALUE,
                        List.of(
                                authority("ROLE_USER")
                        )
                );

        assertThat(zeroVersion.getTokenVersion())
                .isZero();

        assertThat(maximumVersion.getTokenVersion())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullAuthoritiesAreRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        0L,
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "authorities не должны быть null"
                );
    }

    @Test
    void emptyAuthoritiesAreRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of()
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "authorities не должны быть пустыми"
                );
    }

    @Test
    void unknownAuthorityIsRejected() {
        assertThatThrownBy(() ->
                SafeAiUserPrincipal.passwordPrincipal(
                        USER_ID,
                        ORGANIZATION_ID,
                        EMAIL,
                        PASSWORD_HASH,
                        true,
                        0L,
                        List.of(
                                authority("ROLE_ROOT")
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "Unknown role"
                );
    }

    private SafeAiUserPrincipal passwordPrincipal() {
        return SafeAiUserPrincipal.passwordPrincipal(
                USER_ID,
                ORGANIZATION_ID,
                EMAIL,
                PASSWORD_HASH,
                true,
                3L,
                List.of(
                        authority("ROLE_USER")
                )
        );
    }

    private SimpleGrantedAuthority authority(
            String value
    ) {
        return new SimpleGrantedAuthority(value);
    }
}