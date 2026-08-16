package ru.safeai.gateway.common.security;

import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Безопасное представление authenticated user.
 *
 * <p>Password-auth principal временно содержит canonical email и password
 * hash, необходимый DaoAuthenticationProvider. JWT-auth principal не хранит
 * email и вообще не содержит credentials/PII, которых нет в access token.</p>
 */
public final class SafeAiUserPrincipal
        implements UserDetails, CredentialsContainer {

    @Getter
    private final UUID id;

    @Getter
    private final UUID organizationId;

    private final @Nullable String email;
    private final boolean enabled;

    @Getter
    private final long tokenVersion;

    @Getter
    private final long organizationAuthVersion;

    private final List<String> authorityNames;
    private final List<GrantedAuthority> authorities;

    private @Nullable String passwordHash;

    private SafeAiUserPrincipal(
            UUID id,
            UUID organizationId,
            @Nullable String email,
            @Nullable String passwordHash,
            boolean enabled,
            long tokenVersion,
            long organizationAuthVersion,
            Collection<? extends GrantedAuthority> authorities
    ) {
        this.id = Objects.requireNonNull(
                id,
                "id не должен быть null"
        );

        this.organizationId = Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        this.email = email == null
                ? null
                : SecurityIdentityValidator
                .requireCanonicalEmail(email);

        this.passwordHash = passwordHash;
        this.enabled = enabled;

        this.tokenVersion =
                SecurityIdentityValidator
                        .requireNonNegativeVersion(
                                tokenVersion,
                                "tokenVersion"
                        );

        this.organizationAuthVersion =
                SecurityIdentityValidator
                        .requireNonNegativeVersion(
                                organizationAuthVersion,
                                "organizationAuthVersion"
                        );

        this.authorityNames =
                SecurityIdentityValidator
                        .normalizeAuthorityNames(
                                authorities
                        );

        this.authorities = createAuthorities(
                authorityNames
        );
    }

    public static SafeAiUserPrincipal passwordPrincipal(
            UUID id,
            UUID organizationId,
            String email,
            String passwordHash,
            boolean enabled,
            long tokenVersion,
            long organizationAuthVersion,
            Collection<? extends GrantedAuthority> authorities
    ) {
        String credentials = Objects.requireNonNull(
                passwordHash,
                "passwordHash не должен быть null"
        );

        if (credentials.isBlank()) {
            throw new IllegalArgumentException(
                    "passwordHash не должен быть пустым"
            );
        }

        return new SafeAiUserPrincipal(
                id,
                organizationId,
                Objects.requireNonNull(
                        email,
                        "email не должен быть null"
                ),
                credentials,
                enabled,
                tokenVersion,
                organizationAuthVersion,
                authorities
        );
    }

    public static SafeAiUserPrincipal accessTokenPrincipal(
            UUID id,
            UUID organizationId,
            long tokenVersion,
            long organizationAuthVersion,
            Collection<? extends GrantedAuthority> authorities
    ) {
        return new SafeAiUserPrincipal(
                id,
                organizationId,
                null,
                null,
                true,
                tokenVersion,
                organizationAuthVersion,
                authorities
        );
    }

    /**
     * Возвращает email только для password-auth principal.
     * JWT-auth principal намеренно не содержит email.
     */
    public @Nullable String getEmail() {
        return email;
    }

    public List<String> authorityNames() {
        return authorityNames;
    }

    @Override
    public List<GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public @Nullable String getPassword() {
        return passwordHash;
    }

    /**
     * Для password-auth сохраняется привычный username=email.
     * Для JWT-auth username является стабильным non-PII userId.
     */
    @Override
    public String getUsername() {
        return email != null
                ? email
                : id.toString();
    }

    /*
     * isAccountNonExpired(), isAccountNonLocked() и
     * isCredentialsNonExpired() намеренно не переопределяются:
     * в используемой версии Spring Security UserDetails уже содержит
     * default-реализации, возвращающие true.
     */

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public String toString() {
        return "SafeAiUserPrincipal{"
                + "id=" + id
                + ", organizationId=" + organizationId
                + ", enabled=" + enabled
                + ", tokenVersion=" + tokenVersion
                + ", organizationAuthVersion="
                + organizationAuthVersion
                + ", authorities=" + authorityNames
                + '}';
    }

    private static List<GrantedAuthority> createAuthorities(
            List<String> authorityNames
    ) {
        ArrayList<GrantedAuthority> result =
                new ArrayList<>(authorityNames.size());

        for (String authorityName : authorityNames) {
            result.add(
                    new SimpleGrantedAuthority(
                            authorityName
                    )
            );
        }

        return List.copyOf(result);
    }
}