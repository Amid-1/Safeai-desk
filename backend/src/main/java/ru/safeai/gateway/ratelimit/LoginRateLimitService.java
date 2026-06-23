package ru.safeai.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(LoginRateLimitProperties.class)
public class LoginRateLimitService {

    private static final String EMAIL_KEY_PREFIX = "rate-limit:login:email:";
    private static final String IP_KEY_PREFIX = "rate-limit:login:ip:";

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final LoginRateLimitProperties properties;

    public void checkAllowed(String email, String ipAddress) {
        if (!properties.isEnabled()) {
            return;
        }

        String normalizedEmail = normalizeEmail(email);
        String normalizedIp = normalizeIp(ipAddress);
        Duration window = properties.effectiveWindow();

        checkKey(
                emailKey(normalizedEmail),
                properties.effectiveEmailLimit(),
                window,
                "Слишком много попыток входа для этого email. Попробуйте позже"
        );

        checkKey(
                ipKey(normalizedIp),
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
            rateLimiter.reset(emailKey(normalizedEmail));
        } catch (RuntimeException exception) {
            log.warn("Failed to reset login email rate limit");
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

    private String emailKey(String normalizedEmail) {
        return EMAIL_KEY_PREFIX + sha256(normalizedEmail);
    }

    private String ipKey(String normalizedIp) {
        return IP_KEY_PREFIX + sha256(normalizedIp);
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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}