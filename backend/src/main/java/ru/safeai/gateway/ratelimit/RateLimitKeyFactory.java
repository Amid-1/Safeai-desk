package ru.safeai.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(RateLimitRedisKeyProperties.class)
public class RateLimitKeyFactory {

    private final RateLimitRedisKeyProperties properties;

    public String loginEmail(String normalizedEmail) {
        return withPrefix("rate-limit:login:email:" + sha256(normalizedEmail));
    }

    public String loginIp(String normalizedIp) {
        return withPrefix("rate-limit:login:ip:" + sha256(normalizedIp));
    }

    public String aiMessageUser(UUID userId) {
        return withPrefix("rate-limit:ai-message:user:" + sha256(userId.toString()));
    }

    private String withPrefix(String key) {
        return properties.effectiveKeyPrefix() + ":" + key;
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