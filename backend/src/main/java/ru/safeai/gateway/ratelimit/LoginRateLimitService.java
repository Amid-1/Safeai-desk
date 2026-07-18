package ru.safeai.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.time.Duration;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(LoginRateLimitProperties.class)
public class LoginRateLimitService {

    private static final String PUBLIC_LIMIT_MESSAGE =
            "Слишком много попыток входа. Попробуйте позже";

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final LoginRateLimitProperties properties;
    private final RateLimitKeyFactory keyFactory;

    /**
     * Fail-closed strategy.

     * Email and IP counters are always incremented atomically by one
     * Redis Lua script. A failure of Redis blocks the login attempt.
     */
    public void checkAllowed(
            String email,
            String ipAddress
    ) {
        if (!properties.isEnabled()) {
            return;
        }

        String normalizedEmail = normalizeEmail(email);
        String normalizedIp = normalizeIp(ipAddress);

        try {
            DualRateLimitResult result =
                    rateLimiter.incrementBothAndCheck(
                            keyFactory.loginEmail(normalizedEmail),
                            properties.effectiveEmailLimit(),
                            keyFactory.loginIp(normalizedIp),
                            properties.effectiveIpLimit(),
                            properties.effectiveWindow()
                    );

            if (!result.allowed()) {
                throw new RateLimitExceededException(
                        PUBLIC_LIMIT_MESSAGE,
                        Duration.ofSeconds(
                                result.retryAfterSeconds()
                        )
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

    public void resetEmailLimit(String email) {
        if (!properties.isEnabled()) {
            return;
        }

        String normalizedEmail = normalizeEmail(email);

        try {
            rateLimiter.reset(
                    keyFactory.loginEmail(normalizedEmail)
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to reset login email rate limit",
                    exception
            );
        }
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank()
                ? "unknown"
                : email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeIp(String ipAddress) {
        return ipAddress == null || ipAddress.isBlank()
                ? "unknown"
                : ipAddress.trim();
    }
}
