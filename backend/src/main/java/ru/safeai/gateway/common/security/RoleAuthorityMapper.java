package ru.safeai.gateway.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class RoleAuthorityMapper {

    private static final String ROLE_PREFIX = "ROLE_";

    private RoleAuthorityMapper() {
    }

    public static Set<SimpleGrantedAuthority> toAuthorities(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }

        return roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .map(RoleAuthorityMapper::withRolePrefix)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static List<String> toRoleNames(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null || authorities.isEmpty()) {
            return List.of();
        }

        return authorities.stream()
                .filter(Objects::nonNull)
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(authority -> !authority.isBlank())
                .map(authority -> authority.toUpperCase(Locale.ROOT))
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(RoleAuthorityMapper::withoutRolePrefix)
                .distinct()
                .toList();
    }

    private static String withRolePrefix(String role) {
        String normalized = role.trim().toUpperCase(Locale.ROOT);

        return normalized.startsWith(ROLE_PREFIX)
                ? normalized
                : ROLE_PREFIX + normalized;
    }

    private static String withoutRolePrefix(String authority) {
        String normalized = authority.trim().toUpperCase(Locale.ROOT);

        return normalized.startsWith(ROLE_PREFIX)
                ? normalized.substring(ROLE_PREFIX.length())
                : normalized;
    }
}