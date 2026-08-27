package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.RoleAuthorityMapper;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.mapper.UserRoleMapper;
import ru.safeai.gateway.user.repository.UserRepository;
import ru.safeai.gateway.user.validation.UserEmailNormalizer;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginSessionTransactionService {

    private static final String SECURITY_STATE_CHANGED_MESSAGE =
            "Security state changed during login";

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    /**
     * Короткая транзакция создания login session.
     *
     * <p>Password hash должен быть проверен до входа в этот метод.
     * Pessimistic user lock синхронизирует login с изменениями
     * password, role, email, enabled и другого security state.</p>
     *
     * <p>lastLoginAt обновляется отдельным bulk update без изменения
     * optimistic {@code @Version}. Обычный успешный login поэтому
     * не создаёт ложный business version conflict для административных
     * операций над пользователем.</p>
     */
    @Transactional
    public LoginSessionResult createSession(
            SafeAiUserPrincipal authenticatedPrincipal,
            HttpServletRequest request
    ) {
        Objects.requireNonNull(
                authenticatedPrincipal,
                "authenticatedPrincipal не должен быть null"
        );

        Objects.requireNonNull(
                request,
                "request не должен быть null"
        );

        UserEntity user =
                userRepository
                        .findByIdForSecurityUpdate(
                                authenticatedPrincipal.getId()
                        )
                        .orElseThrow(
                                this::securityStateChanged
                        );

        UUID organizationId =
                user.getOrganization()
                        .getId();

        long organizationAuthVersion =
                user.getOrganization()
                        .getAuthVersion();

        String canonicalEmail =
                UserEmailNormalizer.normalizeStored(
                        user.getEmail()
                );

        Set<String> roleNames =
                UserRoleMapper.toRoleNames(
                        user
                );

        Set<String> authenticatedRoleNames =
                authenticatedRoleNames(
                        authenticatedPrincipal
                );

        validateAuthenticatedSnapshot(
                authenticatedPrincipal,
                user,
                organizationId,
                organizationAuthVersion,
                canonicalEmail,
                roleNames,
                authenticatedRoleNames
        );

        Instant now =
                clock.instant();

        int updatedLastLoginRows =
                userRepository
                        .updateLastLoginAtWithoutVersion(
                                user.getId(),
                                now
                        );

        if (updatedLastLoginRows != 1) {
            throw securityStateChanged();
        }

        RefreshTokenService.CreatedRefreshToken refreshToken =
                refreshTokenService.createForLogin(
                        user,
                        request,
                        now
                );

        AccessTokenSubject accessTokenSubject =
                new AccessTokenSubject(
                        user.getId(),
                        organizationId,
                        user.getTokenVersion(),
                        organizationAuthVersion,
                        roleNames
                );

        CurrentUserResponse currentUser =
                new CurrentUserResponse(
                        user.getId(),
                        organizationId,
                        canonicalEmail,
                        user.getFullName(),
                        true,
                        roleNames
                );

        return new LoginSessionResult(
                currentUser,
                accessTokenSubject,
                refreshToken.rawToken(),
                refreshToken.cookieMaxAge()
        );
    }

    private void validateAuthenticatedSnapshot(
            SafeAiUserPrincipal principal,
            UserEntity user,
            UUID organizationId,
            long organizationAuthVersion,
            String canonicalEmail,
            Set<String> currentRoleNames,
            Set<String> authenticatedRoleNames
    ) {
        boolean valid =
                user.isEnabled()
                        && user.getOrganization()
                        .isEnabled()
                        && organizationId.equals(
                        principal.getOrganizationId()
                )
                        && organizationAuthVersion
                        == principal.getOrganizationAuthVersion()
                        && canonicalEmail.equals(
                        principal.getEmail()
                )
                        && user.getTokenVersion()
                        == principal.getTokenVersion()
                        && currentRoleNames.equals(
                        authenticatedRoleNames
                );

        if (!valid) {
            throw securityStateChanged();
        }
    }

    private Set<String> authenticatedRoleNames(
            SafeAiUserPrincipal principal
    ) {
        try {
            return Set.copyOf(
                    RoleAuthorityMapper.toRoleNames(
                            principal.getAuthorities()
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new BadCredentialsException(
                    SECURITY_STATE_CHANGED_MESSAGE,
                    exception
            );
        }
    }

    private BadCredentialsException securityStateChanged() {
        return new BadCredentialsException(
                SECURITY_STATE_CHANGED_MESSAGE
        );
    }
}