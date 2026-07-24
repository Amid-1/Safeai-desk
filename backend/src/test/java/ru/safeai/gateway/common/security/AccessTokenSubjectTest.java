package ru.safeai.gateway.common.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenSubjectTest {

    private static final UUID USER_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final UUID ORGANIZATION_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void constructorCreatesSubjectWithExpectedValues() {
        AccessTokenSubject subject = new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                7L,
                Set.of("USER")
        );

        assertThat(subject.userId()).isEqualTo(USER_ID);
        assertThat(subject.organizationId()).isEqualTo(ORGANIZATION_ID);
        assertThat(subject.email()).isEqualTo("user@example.com");
        assertThat(subject.tokenVersion()).isEqualTo(7L);
        assertThat(subject.roles()).containsExactly("USER");
    }

    @Test
    void rolesAreCanonicalizedDeduplicatedAndSorted() {
        AccessTokenSubject subject = new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                1L,
                Set.of(
                        "role_user",
                        "USER",
                        "admin",
                        "ROLE_SUPER_ADMIN"
                )
        );

        assertThat(subject.roles())
                .containsExactly(
                        "ADMIN",
                        "SUPER_ADMIN",
                        "USER"
                );
    }

    @Test
    void rolesAreImmutable() {
        AccessTokenSubject subject = validSubject(
                new LinkedHashSet<>(Set.of("USER"))
        );

        assertThatThrownBy(() ->
                subject.roles().add("ADMIN")
        ).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() ->
                subject.roles().remove("USER")
        ).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() ->
                subject.roles().clear()
        ).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rolesAreDefensivelyCopied() {
        LinkedHashSet<String> sourceRoles = new LinkedHashSet<>();
        sourceRoles.add("USER");

        AccessTokenSubject subject = validSubject(sourceRoles);

        sourceRoles.clear();
        sourceRoles.add("ADMIN");

        assertThat(subject.roles())
                .containsExactly("USER");
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullUserIdIsRejected() {
        assertThatThrownBy(() -> new AccessTokenSubject(
                null,
                ORGANIZATION_ID,
                "user@example.com",
                0L,
                Set.of("USER")
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userId не должен быть null");
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullOrganizationIdIsRejected() {
        assertThatThrownBy(() -> new AccessTokenSubject(
                USER_ID,
                null,
                "user@example.com",
                0L,
                Set.of("USER")
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("organizationId не должен быть null");
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullEmailIsRejected() {
        assertThatThrownBy(() -> new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                null,
                0L,
                Set.of("USER")
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("email не должен быть null");
    }

    @Test
    void blankEmailIsRejected() {
        assertInvalidEmail(" ");
    }

    @Test
    void emailWithLeadingWhitespaceIsRejected() {
        assertInvalidEmail(" user@example.com");
    }

    @Test
    void emailWithTrailingWhitespaceIsRejected() {
        assertInvalidEmail("user@example.com ");
    }

    @Test
    void uppercaseEmailIsRejectedBeforeTokenIssuance() {
        assertInvalidEmail("User@example.com");
    }

    @Test
    void emailLongerThan255CharactersIsRejected() {
        String email = "a".repeat(244) + "@example.com";

        assertThat(email.length()).isGreaterThan(255);
        assertInvalidEmail(email);
    }

    @Test
    void emailWithExactly255CharactersIsAccepted() {
        String email = "a".repeat(243) + "@example.com";

        assertThat(email).hasSize(255);

        AccessTokenSubject subject = new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                email,
                0L,
                Set.of("USER")
        );

        assertThat(subject.email()).isEqualTo(email);
    }

    @Test
    void negativeTokenVersionIsRejected() {
        assertThatThrownBy(() -> new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                -1L,
                Set.of("USER")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tokenVersion не может быть отрицательным");
    }

    @Test
    void zeroTokenVersionIsAccepted() {
        AccessTokenSubject subject = new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                0L,
                Set.of("USER")
        );

        assertThat(subject.tokenVersion()).isZero();
    }

    @Test
    void maximumTokenVersionIsAccepted() {
        AccessTokenSubject subject = new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                Long.MAX_VALUE,
                Set.of("USER")
        );

        assertThat(subject.tokenVersion()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void nullRolesAreRejected() {
        assertThatThrownBy(() -> new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                0L,
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("roles не должен быть null");
    }

    @Test
    void emptyRolesAreRejected() {
        assertThatThrownBy(() -> new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                0L,
                Set.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("roles не должен быть пустым");
    }

    @Test
    void unknownRoleIsRejected() {
        assertThatThrownBy(() -> new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                0L,
                Set.of("ROOT")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown role");
    }

    @Test
    void recordEqualityUsesNormalizedValues() {
        AccessTokenSubject first = new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                1L,
                Set.of("role_user", "admin")
        );

        AccessTokenSubject second = new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                1L,
                Set.of("USER", "ROLE_ADMIN")
        );

        assertThat(first)
                .isEqualTo(second)
                .hasSameHashCodeAs(second);
    }

    private AccessTokenSubject validSubject(Set<String> roles) {
        return new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                "user@example.com",
                1L,
                roles
        );
    }

    private void assertInvalidEmail(String email) {
        assertThatThrownBy(() -> new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                email,
                0L,
                Set.of("USER")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "каноническим lowercase email"
                );
    }
}