package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;
import ru.safeai.gateway.identity.session.SessionRevocationPort;
import ru.safeai.gateway.identity.session.SessionRevocationReason;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionRevocationService implements SessionRevocationPort {

    private static final Set<SessionRevocationReason>
            USER_REVOCATION_REASONS =
            EnumSet.of(
                    SessionRevocationReason.PASSWORD_RESET,
                    SessionRevocationReason.ROLE_CHANGED,
                    SessionRevocationReason.EMAIL_CHANGED,
                    SessionRevocationReason.USER_DISABLED,
                    SessionRevocationReason.SECURITY_STATE_CHANGED,
                    SessionRevocationReason.ADMIN_REVOKED
            );

    private static final Set<SessionRevocationReason>
            ORGANIZATION_REVOCATION_REASONS =
            EnumSet.of(
                    SessionRevocationReason.ORGANIZATION_DISABLED,
                    SessionRevocationReason.SECURITY_STATE_CHANGED,
                    SessionRevocationReason.ADMIN_REVOKED
            );

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    /**
     * Отзывает все refresh-token rows пользователя.
     *
     * <p>Метод должен вызываться внутри транзакции изменения
     * security state пользователя.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void revokeAllForUser(
            UUID userId,
            SessionRevocationReason reason
    ) {
        Objects.requireNonNull(
                userId,
                "userId не должен быть null"
        );

        Objects.requireNonNull(
                reason,
                "reason не должен быть null"
        );

        if (!USER_REVOCATION_REASONS.contains(reason)) {
            throw new IllegalArgumentException(
                    "Причина не допускается для отзыва сессий "
                            + "пользователя: "
                            + reason
            );
        }

        Instant revokedAt = clock.instant();

        int affectedRows =
                refreshTokenRepository.terminateAllByUserId(
                        userId,
                        revokedAt,
                        toRefreshTokenReason(reason)
                );

        logResult(
                "user",
                userId,
                affectedRows,
                reason
        );
    }

    /**
     * Отзывает все refresh-token rows пользователей организации.
     *
     * <p>Метод должен вызываться внутри транзакции изменения
     * security state организации.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void revokeAllForOrganization(
            UUID organizationId,
            SessionRevocationReason reason
    ) {
        Objects.requireNonNull(
                organizationId,
                "organizationId не должен быть null"
        );

        Objects.requireNonNull(
                reason,
                "reason не должен быть null"
        );

        if (!ORGANIZATION_REVOCATION_REASONS.contains(reason)) {
            throw new IllegalArgumentException(
                    "Причина не допускается для отзыва сессий "
                            + "организации: "
                            + reason
            );
        }

        Instant revokedAt = clock.instant();

        int affectedRows =
                refreshTokenRepository
                        .terminateAllByOrganizationId(
                                organizationId,
                                revokedAt,
                                toRefreshTokenReason(reason)
                        );

        logResult(
                "organization",
                organizationId,
                affectedRows,
                reason
        );
    }

    private static RefreshTokenRevocationReason toRefreshTokenReason(
            SessionRevocationReason reason
    ) {
        return switch (reason) {
            case PASSWORD_RESET ->
                    RefreshTokenRevocationReason.PASSWORD_RESET;
            case ROLE_CHANGED ->
                    RefreshTokenRevocationReason.ROLE_CHANGED;
            case EMAIL_CHANGED ->
                    RefreshTokenRevocationReason.EMAIL_CHANGED;
            case USER_DISABLED ->
                    RefreshTokenRevocationReason.USER_DISABLED;
            case ORGANIZATION_DISABLED ->
                    RefreshTokenRevocationReason.ORGANIZATION_DISABLED;
            case SECURITY_STATE_CHANGED ->
                    RefreshTokenRevocationReason.SECURITY_STATE_CHANGED;
            case ADMIN_REVOKED ->
                    RefreshTokenRevocationReason.ADMIN_REVOKED;
        };
    }

    private void logResult(
            String subjectType,
            UUID subjectId,
            int affectedRows,
            SessionRevocationReason reason
    ) {
        if (affectedRows > 0) {
            log.info(
                    "Refresh sessions revoked: subjectType={}, "
                            + "subjectId={}, affectedRows={}, reason={}",
                    subjectType,
                    subjectId,
                    affectedRows,
                    reason
            );

            return;
        }

        log.debug(
                "No refresh sessions found for revocation: "
                        + "subjectType={}, subjectId={}, reason={}",
                subjectType,
                subjectId,
                reason
        );
    }
}