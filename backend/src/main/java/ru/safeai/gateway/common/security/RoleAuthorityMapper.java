package ru.safeai.gateway.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
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
                .collect(Collectors.toSet());
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
                .map(RoleAuthorityMapper::withoutRolePrefix)
                .toList();
    }

    private static String withRolePrefix(String role) {
        return role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role;
    }

    private static String withoutRolePrefix(String authority) {
        return authority.startsWith(ROLE_PREFIX)
                ? authority.substring(ROLE_PREFIX.length())
                : authority;
    }
}