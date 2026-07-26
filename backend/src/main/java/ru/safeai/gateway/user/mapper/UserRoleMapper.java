package ru.safeai.gateway.user.mapper;

import ru.safeai.gateway.common.security.SystemRole;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class UserRoleMapper {

    private UserRoleMapper() {
    }

    public static Set<String> toRoleNames(UserEntity user) {
        Objects.requireNonNull(user, "user не должен быть null");

        TreeSet<String> roleNames = user.getRoles()
                .stream()
                .map(UserRoleMapper::requireRoleName)
                .collect(Collectors.toCollection(TreeSet::new));

        if (roleNames.size() != 1) {
            throw new IllegalStateException(
                    "У пользователя должна быть ровно одна системная роль: "
                            + "userId=" + user.getId()
                            + ", roles=" + roleNames
            );
        }

        return Collections.unmodifiableSet(
                new LinkedHashSet<>(roleNames)
        );
    }

    private static String requireRoleName(RoleEntity role) {
        Objects.requireNonNull(role, "role не должен быть null");

        String roleName = role.getName();

        if (roleName == null || roleName.isBlank()) {
            throw new IllegalStateException(
                    "RoleEntity должен содержать непустое имя: roleId="
                            + role.getId()
            );
        }

        try {
            return SystemRole.parse(roleName).roleName();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "RoleEntity содержит неизвестную системную роль: roleId="
                            + role.getId() + ", name=" + roleName,
                    exception
            );
        }
    }
}
