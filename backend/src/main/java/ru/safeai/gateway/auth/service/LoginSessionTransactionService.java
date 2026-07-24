package ru.safeai.gateway.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.dto.CurrentUserResponse;
import ru.safeai.gateway.common.security.AccessTokenSubject;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.user.entity.UserEntity;
import ru.safeai.gateway.user.mapper.UserRoleMapper;
import ru.safeai.gateway.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginSessionTransactionService {

    private static final int MAX_EMAIL_LENGTH = 255;

    private static final String SECURITY_STATE_CHANGED_MESSAGE =
            "Security state changed during login";

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    /**
     * Короткая транзакция создания login session.
     *
     * <p>Проверка password hash должна завершиться до входа
     * в этот метод. Pessimistic user lock синхронизирует login
     * с password, role, email и enabled mutations.</p>
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

        UserEntity user = userRepository
                .findByIdForSecurityUpdate(
                        authenticatedPrincipal.getId()
                )
                .orElseThrow(this::securityStateChanged);

        /*
         * Обращение к organization и roles происходит внутри
         * активной транзакции. Это одновременно инициализирует
         * LAZY relationships без бессмысленных ignored calls.
         */
        UUID organizationId =
                user.getOrganization().getId();

        String canonicalEmail =
                canonicalEmail(user.getEmail());

        Set<String> roleNames =
                UserRoleMapper.toRoleNames(user);

        validateAuthenticatedSnapshot(
                authenticatedPrincipal,
                user,
                organizationId,
                canonicalEmail,
                roleNames
        );

        Instant now = clock.instant();

        user.setLastLoginAt(now);

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
                        canonicalEmail,
                        user.getTokenVersion(),
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
            String canonicalEmail,
            Set<String> currentRoleNames
    ) {
        boolean valid =
                user.isEnabled()
                        && user.getOrganization().isEnabled()
                        && organizationId.equals(
                        principal.getOrganizationId()
                )
                        && canonicalEmail.equals(
                        principal.getEmail()
                )
                        && user.getTokenVersion()
                        == principal.getTokenVersion()
                        && currentRoleNames.equals(
                        principal.authorityNames()
                );

        if (!valid) {
            throw securityStateChanged();
        }
    }

    private BadCredentialsException securityStateChanged() {
        return new BadCredentialsException(
                SECURITY_STATE_CHANGED_MESSAGE
        );
    }

    private String canonicalEmail(
            String email
    ) {
        Objects.requireNonNull(
                email,
                "email пользователя не должен быть null"
        );

        String canonical = email
                .trim()
                .toLowerCase(Locale.ROOT);

        if (canonical.isBlank()
                || canonical.length() > MAX_EMAIL_LENGTH) {
            throw new IllegalStateException(
                    "Некорректный canonical email пользователя"
            );
        }

        return canonical;
    }
}