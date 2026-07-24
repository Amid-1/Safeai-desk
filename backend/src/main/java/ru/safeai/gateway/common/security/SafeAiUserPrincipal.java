package ru.safeai.gateway.common.security;

import lombok.Getter;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SafeAiUserPrincipal
        implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final int MAX_EMAIL_LENGTH = 255;

    /**
     * Значение используется после удаления credentials,
     * а также для principal, созданного из проверенного access JWT.
     */
    private static final String ERASED_PASSWORD = "";

    @Getter
    private final UUID id;

    @Getter
    private final UUID organizationId;

    @Getter
    private final String email;

    /**
     * Поле намеренно изменяемое.
     * ProviderManager вызывает eraseCredentials()
     * после успешной password authentication.
     */
    private String passwordHash;

    private final boolean enabled;

    @Getter
    private final long tokenVersion;

    private final Set<GrantedAuthority> authorities;

    private SafeAiUserPrincipal(
            UUID id,
            UUID organizationId,
            String email,
            String passwordHash,
            boolean enabled,
            long tokenVersion,
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

        this.email = requireCanonicalEmail(email);

        this.passwordHash = Objects.requireNonNull(
                passwordHash,
                "passwordHash не должен быть null"
        );

        this.enabled = enabled;

        if (tokenVersion < 0) {
            throw new IllegalArgumentException(
                    "tokenVersion не может быть отрицательным"
            );
        }

        this.tokenVersion = tokenVersion;
        this.authorities = normalizeAuthorities(authorities);
    }

    /**
     * Создаёт principal для аутентификации по email и паролю.
     */
    public static SafeAiUserPrincipal passwordPrincipal(
            UUID id,
            UUID organizationId,
            String email,
            String passwordHash,
            boolean enabled,
            long tokenVersion,
            Collection<? extends GrantedAuthority> authorities
    ) {
        return new SafeAiUserPrincipal(
                id,
                organizationId,
                email,
                requirePasswordHash(passwordHash),
                enabled,
                tokenVersion,
                authorities
        );
    }

    /**
     * Создаёт principal для уже проверенного access JWT.
     *
     * <p>Password hash в JWT principal никогда не помещается.
     * Актуальный статус пользователя дополнительно проверяется
     * в {@code UserStatusFilter}.</p>
     */
    public static SafeAiUserPrincipal accessTokenPrincipal(
            UUID id,
            UUID organizationId,
            String email,
            long tokenVersion,
            Collection<? extends GrantedAuthority> authorities
    ) {
        return new SafeAiUserPrincipal(
                id,
                organizationId,
                email,
                ERASED_PASSWORD,
                true,
                tokenVersion,
                authorities
        );
    }

    /**
     * Возвращает канонические названия Spring Security authorities,
     * например {@code ROLE_ADMIN} и {@code ROLE_USER}.
     */
    public Set<String> authorityNames() {
        LinkedHashSet<String> names = authorities.stream()
                .map(SafeAiUserPrincipal::requireAuthorityName)
                .collect(Collectors.toCollection(
                        LinkedHashSet::new
                ));

        return Collections.unmodifiableSet(names);
    }

    @Override
    public Set<GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /**
     * До успешной password authentication возвращает password hash.
     *
     * <p>После вызова {@link #eraseCredentials()} возвращает пустую
     * строку, что сохраняет совместимость с non-null контрактом
     * Spring Security 6.x.</p>
     */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * Идемпотентно удаляет ссылку на реальный password hash.
     */
    @Override
    public void eraseCredentials() {
        passwordHash = ERASED_PASSWORD;
    }

    /**
     * Намеренно не выводит email и password hash.
     */
    @Override
    public String toString() {
        return "SafeAiUserPrincipal{"
                + "id=" + id
                + ", organizationId=" + organizationId
                + ", enabled=" + enabled
                + ", tokenVersion=" + tokenVersion
                + ", authorities=" + authorities
                + '}';
    }

    private static String requireCanonicalEmail(
            String value
    ) {
        Objects.requireNonNull(
                value,
                "email не должен быть null"
        );

        if (value.isBlank()
                || value.length() > MAX_EMAIL_LENGTH
                || !value.equals(value.trim())
                || !value.equals(
                value.toLowerCase(Locale.ROOT)
        )) {
            throw new IllegalArgumentException(
                    "email должен быть canonical lowercase email"
            );
        }

        return value;
    }

    private static String requirePasswordHash(
            String value
    ) {
        Objects.requireNonNull(
                value,
                "passwordHash не должен быть null"
        );

        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "passwordHash не должен быть пустым"
            );
        }

        return value;
    }

    private static Set<GrantedAuthority> normalizeAuthorities(
            Collection<? extends GrantedAuthority> authorities
    ) {
        Objects.requireNonNull(
                authorities,
                "authorities не должны быть null"
        );

        var roleNames = RoleAuthorityMapper.toRoleNames(
                authorities
        );

        if (roleNames.isEmpty()) {
            throw new IllegalArgumentException(
                    "authorities не должны быть пустыми"
            );
        }

        LinkedHashSet<GrantedAuthority> normalized =
                RoleAuthorityMapper.toAuthorities(roleNames)
                        .stream()
                        .sorted(Comparator.comparing(
                                SafeAiUserPrincipal::requireAuthorityName
                        ))
                        .collect(Collectors.toCollection(
                                LinkedHashSet::new
                        ));

        return Collections.unmodifiableSet(normalized);
    }

    /**
     * GrantedAuthority#getAuthority() формально может вернуть null.
     * В SafeAI допускаются только authorities с непустым строковым именем.
     */
    private static String requireAuthorityName(
            GrantedAuthority authority
    ) {
        Objects.requireNonNull(
                authority,
                "authority не должен быть null"
        );

        String authorityName = authority.getAuthority();

        if (authorityName == null || authorityName.isBlank()) {
            throw new IllegalStateException(
                    "GrantedAuthority должен иметь непустое "
                            + "строковое представление"
            );
        }

        return authorityName;
    }
}