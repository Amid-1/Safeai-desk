package ru.safeai.gateway.common.security;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RoleAuthorityMapper {

    private RoleAuthorityMapper() {
    }

    public static List<String> normalizeRoleNames(
            Collection<@Nullable String> roles
    ) {
        Objects.requireNonNull(
                roles,
                "roles не должен быть null"
        );

        return roles.stream()
                .map(role -> Objects.requireNonNull(
                        role,
                        "role element не должен быть null"
                ))
                .map(SystemRole::parse)
                .distinct()
                .sorted(Comparator.comparing(SystemRole::name))
                .map(Enum::name)
                .toList();
    }

    public static Set<SimpleGrantedAuthority> toAuthorities(
            Collection<@Nullable String> roles
    ) {
        List<String> normalizedRoles =
                normalizeRoleNames(roles);

        if (normalizedRoles.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<SimpleGrantedAuthority> result =
                normalizedRoles.stream()
                        .map(SystemRole::valueOf)
                        .map(role -> new SimpleGrantedAuthority(
                                role.authority()
                        ))
                        .collect(
                                java.util.stream.Collectors.toCollection(
                                        LinkedHashSet::new
                                )
                        );

        return Collections.unmodifiableSet(result);
    }

    public static List<String> toRoleNames(
            Collection<? extends @Nullable GrantedAuthority> authorities
    ) {
        Objects.requireNonNull(
                authorities,
                "authorities не должен быть null"
        );

        return authorities.stream()
                .map(authority -> Objects.requireNonNull(
                        authority,
                        "authority element не должен быть null"
                ))
                .map(GrantedAuthority::getAuthority)
                .map(authority -> Objects.requireNonNull(
                        authority,
                        "authority name не должен быть null"
                ))
                .map(SystemRole::parse)
                .distinct()
                .sorted(Comparator.comparing(SystemRole::name))
                .map(Enum::name)
                .toList();
    }
}