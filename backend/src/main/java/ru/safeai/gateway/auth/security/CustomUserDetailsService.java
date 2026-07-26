package ru.safeai.gateway.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.security.RoleAuthorityMapper;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService
        implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(
            String email
    ) throws UsernameNotFoundException {
        String normalizedEmail = Objects.requireNonNull(
                        email,
                        "email не должен быть null"
                )
                .trim()
                .toLowerCase(Locale.ROOT);

        UserEntity user = userRepository
                .findByEmail(normalizedEmail)
                .orElseThrow(() ->
                        new UsernameNotFoundException(
                                "Пользователь не найден: "
                                        + normalizedEmail
                        )
                );

        var organization = Objects.requireNonNull(
                user.getOrganization(),
                "organization пользователя не должна быть null"
        );

        var authorities = RoleAuthorityMapper.toAuthorities(
                user.getRoles()
                        .stream()
                        .map(RoleEntity::getName)
                        .toList()
        );

        return SafeAiUserPrincipal.passwordPrincipal(
                user.getId(),
                organization.getId(),
                normalizedEmail,
                user.getPasswordHash(),
                user.isEnabled()
                        && organization.isEnabled(),
                user.getTokenVersion(),
                organization.getAuthVersion(),
                authorities
        );
    }
}