package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAllForUser(UUID userId) {
        Objects.requireNonNull(userId, "userId не должен быть null");

        int revokedCount = refreshTokenRepository.revokeAllActiveByUserId(
                userId,
                Instant.now()
        );

        if (revokedCount > 0) {
            log.info(
                    "Revoked active refresh sessions for userId={}, count={}",
                    userId,
                    revokedCount
            );
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAllForOrganization(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId не должен быть null");

        int revokedCount = refreshTokenRepository.revokeAllActiveByOrganizationId(
                organizationId,
                Instant.now()
        );

        if (revokedCount > 0) {
            log.info(
                    "Revoked active refresh sessions for organizationId={}, count={}",
                    organizationId,
                    revokedCount
            );
        }
    }
}