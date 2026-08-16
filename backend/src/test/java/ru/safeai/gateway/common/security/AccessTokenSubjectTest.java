package ru.safeai.gateway.common.security;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokenSubjectTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

    private static final UUID ORGANIZATION_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
            );

    @Test
    void constructorCreatesSubjectWithExpectedValues() {
        AccessTokenSubject subject =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        7L,
                        11L,
                        Set.of("USER")
                );

        assertThat(subject.userId())
                .isEqualTo(USER_ID);

        assertThat(subject.organizationId())
                .isEqualTo(ORGANIZATION_ID);

        assertThat(subject.tokenVersion())
                .isEqualTo(7L);

        assertThat(
                subject.organizationAuthVersion()
        ).isEqualTo(11L);

        assertThat(subject.roles())
                .containsExactly("USER");
    }

    @Test
    void rolesAreCanonicalizedDeduplicatedAndSorted() {
        AccessTokenSubject subject =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        2L,
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
        AccessTokenSubject subject =
                validSubject(
                        new LinkedHashSet<>(
                                Set.of("USER")
                        )
                );

        assertThatThrownBy(() ->
                subject.roles().add("ADMIN")
        ).isInstanceOf(
                UnsupportedOperationException.class
        );

        assertThatThrownBy(() ->
                subject.roles().remove("USER")
        ).isInstanceOf(
                UnsupportedOperationException.class
        );

        assertThatThrownBy(() ->
                subject.roles().clear()
        ).isInstanceOf(
                UnsupportedOperationException.class
        );
    }

    @Test
    void rolesAreDefensivelyCopied() {
        LinkedHashSet<String> sourceRoles =
                new LinkedHashSet<>();

        sourceRoles.add("USER");

        AccessTokenSubject subject =
                validSubject(sourceRoles);

        sourceRoles.clear();
        sourceRoles.add("ADMIN");

        assertThat(subject.roles())
                .containsExactly("USER");
    }

    @Test
    void nullUserIdIsRejected() {
        assertThat(
                constructorFailure(
                        null,
                        ORGANIZATION_ID,
                        Set.of("USER")
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "userId не должен быть null"
                );
    }

    @Test
    void nullOrganizationIdIsRejected() {
        assertThat(
                constructorFailure(
                        USER_ID,
                        null,
                        Set.of("USER")
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
    void negativeTokenVersionIsRejected() {
        assertThatThrownBy(() ->
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        -1L,
                        0L,
                        Set.of("USER")
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
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        -1L,
                        Set.of("USER")
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
        AccessTokenSubject subject =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of("USER")
                );

        assertThat(subject.tokenVersion())
                .isZero();

        assertThat(
                subject.organizationAuthVersion()
        ).isZero();
    }

    @Test
    void maximumVersionsAreAccepted() {
        AccessTokenSubject subject =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        Long.MAX_VALUE,
                        Long.MAX_VALUE,
                        Set.of("USER")
                );

        assertThat(subject.tokenVersion())
                .isEqualTo(Long.MAX_VALUE);

        assertThat(
                subject.organizationAuthVersion()
        ).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void nullRolesAreRejected() {
        assertThat(
                constructorFailure(
                        USER_ID,
                        ORGANIZATION_ID,
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "roles не должен быть null"
                );
    }

    @Test
    void emptyRolesAreRejected() {
        assertThatThrownBy(() ->
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of()
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "roles не должен быть пустым"
                );
    }

    @Test
    void unknownRoleIsRejected() {
        assertThatThrownBy(() ->
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        0L,
                        0L,
                        Set.of("ROOT")
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessageContaining(
                        "Unknown role"
                );
    }

    @Test
    void recordEqualityUsesAllNormalizedSecurityValues() {
        AccessTokenSubject first =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        9L,
                        Set.of(
                                "role_user",
                                "admin"
                        )
                );

        AccessTokenSubject second =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        9L,
                        Set.of(
                                "USER",
                                "ROLE_ADMIN"
                        )
                );

        AccessTokenSubject differentEpoch =
                new AccessTokenSubject(
                        USER_ID,
                        ORGANIZATION_ID,
                        1L,
                        10L,
                        Set.of(
                                "USER",
                                "ADMIN"
                        )
                );

        assertThat(first)
                .isEqualTo(second)
                .hasSameHashCodeAs(second);

        assertThat(first)
                .isNotEqualTo(
                        differentEpoch
                );
    }

    @Test
    void canonicalConstructorContainsOnlySecurityClaims()
            throws NoSuchMethodException {
        Constructor<AccessTokenSubject> constructor =
                AccessTokenSubject.class
                        .getDeclaredConstructor(
                                UUID.class,
                                UUID.class,
                                long.class,
                                long.class,
                                Set.class
                        );

        assertThat(constructor)
                .isNotNull();
    }

    @Test
    void noConstructorContainsEmailParameter() {
        for (Constructor<?> constructor :
                AccessTokenSubject.class
                        .getDeclaredConstructors()) {
            assertThat(
                    constructor.getParameterTypes()
            ).doesNotContain(
                    String.class
            );
        }
    }

    private AccessTokenSubject validSubject(
            Set<String> roles
    ) {
        return new AccessTokenSubject(
                USER_ID,
                ORGANIZATION_ID,
                1L,
                2L,
                roles
        );
    }

    /**
     * Проверяет runtime null-contract конструктора без прямой передачи null
     * в параметры AccessTokenSubject, которые static analysis считает @NotNull.
     */
    private Throwable constructorFailure(
            @Nullable UUID userId,
            @Nullable UUID organizationId,
            @Nullable Set<String> roles
    ) {
        try {
            Constructor<AccessTokenSubject> constructor =
                    AccessTokenSubject.class
                            .getDeclaredConstructor(
                                    UUID.class,
                                    UUID.class,
                                    long.class,
                                    long.class,
                                    Set.class
                            );

            constructor.newInstance(
                    userId,
                    organizationId,
                    0L,
                    0L,
                    roles
            );

            throw new AssertionError(
                    "Ожидалось исключение конструктора AccessTokenSubject"
            );
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();

            if (cause == null) {
                throw new AssertionError(
                        "InvocationTargetException не содержит cause",
                        exception
                );
            }

            return cause;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "Не удалось вызвать canonical constructor AccessTokenSubject",
                    exception
            );
        }
    }
}