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

/**
 * Преобразование системных ролей SafeAI в Spring Security authorities
 * и обратно.
 *
 * <p>Единственным source of truth для допустимых ролей является
 * {@link SystemRole}.</p>
 */
public final class RoleAuthorityMapper {

    private RoleAuthorityMapper() {
    }

    /**
     * Преобразует имена ролей:
     *
     * <pre>
     * USER
     * role_user
     * ROLE_USER
     * </pre>
     *
     * в canonical Spring authorities:
     *
     * <pre>
     * ROLE_USER
     * </pre>
     *
     * <p>Результат дедуплицирован, отсортирован
     * и immutable.</p>
     */
    public static Set<SimpleGrantedAuthority> toAuthorities(
            Collection<String> roles
    ) {
        Objects.requireNonNull(
                roles,
                "roles не должен быть null"
        );

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
                        .collect(
                                Collectors.toCollection(
                                        LinkedHashSet::new
                                )
                        );

        return Collections.unmodifiableSet(
                authorities
        );
    }

    /**
     * Преобразует Spring Security authorities в canonical
     * имена системных ролей:
     *
     * <pre>
     * ROLE_ADMIN -> ADMIN
     * ROLE_USER  -> USER
     * </pre>
     *
     * <p>Результат дедуплицирован, отсортирован
     * и immutable.</p>
     */
    public static List<String> toRoleNames(
            Collection<? extends GrantedAuthority> authorities
    ) {
        Objects.requireNonNull(
                authorities,
                "authorities не должен быть null"
        );

        if (authorities.isEmpty()) {
            return List.of();
        }

        return authorities.stream()
                .map(
                        RoleAuthorityMapper
                                ::requireAuthorityName
                )
                .map(SystemRole::parse)
                .map(SystemRole::roleName)
                .distinct()
                .sorted()
                .toList();
    }

    private static String requireAuthorityName(
            GrantedAuthority authority
    ) {
        GrantedAuthority value =
                Objects.requireNonNull(
                        authority,
                        "authority не должен быть null"
                );

        return Objects.requireNonNull(
                value.getAuthority(),
                "GrantedAuthority#getAuthority() "
                        + "не должен возвращать null"
        );
    }
}