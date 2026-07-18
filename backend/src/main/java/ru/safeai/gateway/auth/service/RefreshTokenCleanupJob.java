package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private static final Duration RETENTION = Duration.ofDays(7);

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    @Scheduled(
            cron = "${safeai.auth.refresh-cleanup.cron:0 0 3 * * *}",
            zone = "UTC"
    )
    @Transactional
    public void cleanupExpiredRefreshTokens() {
        Instant threshold = clock.instant().minus(RETENTION);

        int deletedCount =
                refreshTokenRepository.deleteExpiredAndRevokedBefore(threshold);

        if (deletedCount > 0) {
            log.info("Deleted old refresh tokens: count={}", deletedCount);
        }
    }
}
