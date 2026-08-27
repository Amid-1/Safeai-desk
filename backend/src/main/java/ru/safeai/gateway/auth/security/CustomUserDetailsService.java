package ru.safeai.gateway.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.common.exception.BadRequestException;
import ru.safeai.gateway.common.security.RoleAuthorityMapper;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.RoleEntity;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.validation.UserEmailNormalizer;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService
        implements UserDetailsService {

    private static final String USER_NOT_FOUND_MESSAGE =
            "Пользователь не найден";

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(
            String email
    ) throws UsernameNotFoundException {
        String normalizedEmail =
                normalizeAuthenticationEmail(
                        email
                );

        UserEntity user =
                userRepository
                        .findByEmail(
                                normalizedEmail
                        )
                        .orElseThrow(() ->
                                new UsernameNotFoundException(
                                        USER_NOT_FOUND_MESSAGE
                                )
                        );

        var organization =
                Objects.requireNonNull(
                        user.getOrganization(),
                        "organization пользователя не должна быть null"
                );

        var authorities =
                RoleAuthorityMapper.toAuthorities(
                        user.getRoles()
                                .stream()
                                .map(
                                        RoleEntity::getName
                                )
                                .toList()
                );

        String storedEmail =
                UserEmailNormalizer.normalizeStored(
                        user.getEmail()
                );

        return SafeAiUserPrincipal.passwordPrincipal(
                user.getId(),
                organization.getId(),
                storedEmail,
                user.getPasswordHash(),
                user.isEnabled()
                        && organization.isEnabled(),
                user.getTokenVersion(),
                organization.getAuthVersion(),
                authorities
        );
    }

    private String normalizeAuthenticationEmail(
            String email
    ) {
        try {
            return UserEmailNormalizer
                    .normalizeAndValidate(
                            email
                    );
        } catch (BadRequestException exception) {
            throw new UsernameNotFoundException(
                    USER_NOT_FOUND_MESSAGE,
                    exception
            );
        }
    }
}