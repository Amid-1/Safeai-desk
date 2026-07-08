package ru.safeai.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(LoginRateLimitProperties.class)
public class LoginRateLimitService {

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final LoginRateLimitProperties properties;
    private final RateLimitKeyFactory keyFactory;


    /**
     * Fail-closed strategy.
     * Login endpoint is brute-force sensitive.
     * If Redis becomes unavailable, authentication attempts are blocked
     * instead of temporarily disabling rate limiting.
     */
    public void checkAllowed(String email, String ipAddress) {
        if (!properties.isEnabled()) {
            return;
        }

        String normalizedEmail = normalizeEmail(email);
        String normalizedIp = normalizeIp(ipAddress);
        Duration window = properties.effectiveWindow();

        checkKey(
                keyFactory.loginEmail(normalizedEmail),
                properties.effectiveEmailLimit(),
                window,
                "Слишком много попыток входа для этого email. Попробуйте позже"
        );

        checkKey(
                keyFactory.loginIp(normalizedIp),
                properties.effectiveIpLimit(),
                window,
                "Слишком много попыток входа с этого IP. Попробуйте позже"
        );
    }

    public void resetEmailLimit(String email) {
        if (!properties.isEnabled()) {
            return;
        }

        String normalizedEmail = normalizeEmail(email);

        try {
            rateLimiter.reset(keyFactory.loginEmail(normalizedEmail));
        } catch (RuntimeException exception) {
            log.warn("Failed to reset login email rate limit for emailHashKey", exception);
        }
    }

    private void checkKey(String key, int limit, Duration window, String message) {
        try {
            RateLimitResult result = rateLimiter.incrementAndGet(key, window);

            if (result.count() > limit) {
                throw new RateLimitExceededException(
                        message,
                        Duration.ofSeconds(result.ttlSeconds())
                );
            }
        } catch (RateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RateLimitUnavailableException(
                    "Redis login rate limit недоступен",
                    exception
            );
        }
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank()
                ? "unknown"
                : email.trim().toLowerCase();
    }

    private String normalizeIp(String ipAddress) {
        return ipAddress == null || ipAddress.isBlank()
                ? "unknown"
                : ipAddress.trim();
    }
}