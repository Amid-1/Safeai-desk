package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.entity.RefreshTokenRevocationReason;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionRevocationService {

    private static final Set<RefreshTokenRevocationReason>
            USER_REVOCATION_REASONS =
            EnumSet.of(
                    RefreshTokenRevocationReason.PASSWORD_RESET,
                    RefreshTokenRevocationReason.ROLE_CHANGED,
                    RefreshTokenRevocationReason.EMAIL_CHANGED,
                    RefreshTokenRevocationReason.USER_DISABLED,
                    RefreshTokenRevocationReason.SECURITY_STATE_CHANGED,
                    RefreshTokenRevocationReason.ADMIN_REVOKED
            );

    private static final Set<RefreshTokenRevocationReason>
            ORGANIZATION_REVOCATION_REASONS =
            EnumSet.of(
                    RefreshTokenRevocationReason.ORGANIZATION_DISABLED,
                    RefreshTokenRevocationReason.SECURITY_STATE_CHANGED,
                    RefreshTokenRevocationReason.ADMIN_REVOKED
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
    public void revokeAllForUser(
            UUID userId,
            RefreshTokenRevocationReason reason
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
                        reason
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
    public void revokeAllForOrganization(
            UUID organizationId,
            RefreshTokenRevocationReason reason
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
                                reason
                        );

        logResult(
                "organization",
                organizationId,
                affectedRows,
                reason
        );
    }

    private void logResult(
            String subjectType,
            UUID subjectId,
            int affectedRows,
            RefreshTokenRevocationReason reason
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