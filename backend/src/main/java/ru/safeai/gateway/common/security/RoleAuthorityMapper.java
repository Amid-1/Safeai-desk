package ru.safeai.gateway.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class RoleAuthorityMapper {

    private RoleAuthorityMapper() {
    }

    /**
     * Преобразует имена ролей к каноническому формату:

     * USER      -> USER
     * ROLE_USER -> USER
     * user      -> USER

     * Неизвестная, пустая или некорректная роль приводит
     * к исключению в SystemRole.parse().
     */
    public static List<String> normalizeRoleNames(
            Collection<String> roles
    ) {
        if (roles.isEmpty()) {
            return List.of();
        }

        return roles.stream()
                .map(SystemRole::parse)
                .map(SystemRole::roleName)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Преобразует бизнес-роли в Spring Security authorities.
     */
    public static Set<SimpleGrantedAuthority> toAuthorities(
            Collection<String> roles
    ) {
        if (roles.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<SimpleGrantedAuthority> authorities =
                roles.stream()
                        .map(SystemRole::parse)
                        .map(SystemRole::authority)
                        .distinct()
                        .sorted()
                        .map(SimpleGrantedAuthority::new)
                        .collect(Collectors.toCollection(
                                LinkedHashSet::new
                        ));

        return Collections.unmodifiableSet(authorities);
    }

    /**
     * Преобразует Spring Security authorities обратно
     * в канонические имена бизнес-ролей.
     */
    public static List<String> toRoleNames(
            Collection<? extends GrantedAuthority> authorities
    ) {
        if (authorities.isEmpty()) {
            return List.of();
        }

        return authorities.stream()
                .map(RoleAuthorityMapper::requireAuthorityName)
                .map(SystemRole::parse)
                .map(SystemRole::roleName)
                .distinct()
                .sorted()
                .toList();
    }

    private static String requireAuthorityName(
            GrantedAuthority authority
    ) {
        return Objects.requireNonNull(
                authority.getAuthority(),
                "GrantedAuthority#getAuthority() не должен возвращать null"
        );
    }
}