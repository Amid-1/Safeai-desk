package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredRefreshTokens() {
        Instant threshold = Instant.now().minus(Duration.ofDays(7));

        int deletedCount = refreshTokenRepository.deleteExpiredAndRevokedBefore(threshold);

        if (deletedCount > 0) {
            log.info("Deleted old refresh tokens: count={}", deletedCount);
        }
    }
}