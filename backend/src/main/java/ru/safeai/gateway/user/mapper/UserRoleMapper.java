package ru.safeai.gateway.user.mapper;

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

    public static Set<String> toRoleNames(
            UserEntity user
    ) {
        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );

        TreeSet<String> roleNames = user.getRoles()
                .stream()
                .map(UserRoleMapper::requireRoleName)
                .collect(Collectors.toCollection(
                        TreeSet::new
                ));

        if (roleNames.isEmpty()) {
            throw new IllegalStateException(
                    "У пользователя отсутствуют роли: userId="
                            + user.getId()
            );
        }

        return Collections.unmodifiableSet(
                new LinkedHashSet<>(roleNames)
        );
    }

    private static String requireRoleName(
            RoleEntity role
    ) {
        Objects.requireNonNull(
                role,
                "role не должен быть null"
        );

        String roleName = role.getName();

        if (roleName == null || roleName.isBlank()) {
            throw new IllegalStateException(
                    "RoleEntity должен содержать непустое имя: roleId="
                            + role.getId()
            );
        }

        return roleName;
    }
}
