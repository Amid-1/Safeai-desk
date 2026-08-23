package ru.safeai.gateway.user.mapper;

import org.junit.jupiter.api.Test;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRoleMapperTest {

    @Test
    void canonicalizesRoleName() {
        UserEntity user =
                userWithRoles(
                        "role_user"
                );

        assertThat(
                UserRoleMapper.toRoleNames(user)
        ).containsExactly(
                "USER"
        );
    }

    @Test
    void canonicalizesAdminRoleName() {
        UserEntity user =
                userWithRoles(
                        "role_admin"
                );

        assertThat(
                UserRoleMapper.toRoleNames(user)
        ).containsExactly(
                "ADMIN"
        );
    }

    @Test
    void canonicalizesSuperAdminRoleName() {
        UserEntity user =
                userWithRoles(
                        "role_super_admin"
                );

        assertThat(
                UserRoleMapper.toRoleNames(user)
        ).containsExactly(
                "SUPER_ADMIN"
        );
    }

    @Test
    void requiresExactlyOneSystemRole() {
        assertThatThrownBy(() ->
                UserRoleMapper.toRoleNames(
                        userWithRoles(
                                "USER",
                                "ADMIN"
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "ровно одна"
                );
    }

    @Test
    void rejectsEmptyRoleSet() {
        assertThatThrownBy(() ->
                UserRoleMapper.toRoleNames(
                        userWithRoles()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "ровно одна"
                );
    }

    @Test
    void rejectsUnknownRole() {
        assertThatThrownBy(() ->
                UserRoleMapper.toRoleNames(
                        userWithRoles(
                                "ROOT"
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "неизвестную"
                );
    }

    @Test
    void returnedSetIsImmutable() {
        Set<String> roles =
                UserRoleMapper.toRoleNames(
                        userWithRoles(
                                "USER"
                        )
                );

        assertThatThrownBy(() ->
                attemptToAddAdminRole(
                        roles
                )
        ).isInstanceOf(
                UnsupportedOperationException.class
        );

        assertThat(roles)
                .containsExactly(
                        "USER"
                );
    }

    private static void attemptToAddAdminRole(
            Set<String> roles
    ) {
        roles.add(
                "ADMIN"
        );
    }

    private UserEntity userWithRoles(
            String... names
    ) {
        UserEntity user =
                new UserEntity();

        user.setId(
                UUID.randomUUID()
        );

        Set<RoleEntity> roles =
                new HashSet<>();

        for (String name : names) {
            RoleEntity role =
                    new RoleEntity();

            role.setId(
                    UUID.randomUUID()
            );

            role.setName(
                    name
            );

            roles.add(
                    role
            );
        }

        user.setRoles(
                roles
        );

        return user;
    }
}