package ru.safeai.gateway.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.safeai.gateway.auth.repository.RefreshTokenRepository;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredRefreshTokens() {
        Instant threshold = Instant.now().minus(Duration.ofDays(7));

        refreshTokenRepository.deleteExpiredAndRevokedBefore(threshold);
    }
}