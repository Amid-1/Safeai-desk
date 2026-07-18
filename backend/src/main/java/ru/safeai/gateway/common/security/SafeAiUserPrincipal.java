package ru.safeai.gateway.common.security;

import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Getter
public class SafeAiUserPrincipal implements UserDetails {

    private final UUID id;
    private final UUID organizationId;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final Collection<? extends GrantedAuthority> authorities;
    private final long tokenVersion;

    public SafeAiUserPrincipal(
            UUID id,
            UUID organizationId,
            String email,
            String passwordHash,
            boolean enabled,
            long tokenVersion,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this.id = Objects.requireNonNull(id, "id не должен быть null");
        this.organizationId = Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email не должен быть пустым");
        }

        if (!email.equals(email.trim())) {
            throw new IllegalArgumentException(
                    "email не должен содержать внешние пробелы"
            );
        }

        if (tokenVersion < 0) {
            throw new IllegalArgumentException(
                    "tokenVersion не может быть отрицательным"
            );
        }

        this.email = email;
        this.passwordHash = Objects.requireNonNull(
                passwordHash,
                "passwordHash не должен быть null"
        );
        this.enabled = enabled;
        this.tokenVersion = tokenVersion;
        this.authorities = authorities == null
                ? List.of()
                : authorities.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public @NonNull Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public @NonNull String getPassword() {
        return passwordHash;
    }

    @Override
    public @NonNull String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
