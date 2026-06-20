package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(UserStatusCacheProperties.class)
public class UserStatusCacheService {

    private static final String KEY_PREFIX = "user-status:";

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final UserStatusCacheProperties properties;

    public Optional<UserSecurityStatus> getStatus(UUID userId) {
        if (!properties.isEnabled()) {
            return loadFromDatabase(userId);
        }

        String key = key(userId);

        try {
            String cached = redisTemplate.opsForValue().get(key);

            Optional<UserSecurityStatus> cachedStatus = parse(cached);

            if (cachedStatus.isPresent()) {
                return cachedStatus;
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "User status cache unavailable, fallback to PostgreSQL: userId={}",
                    userId,
                    exception
            );
        }

        Optional<UserSecurityStatus> status = loadFromDatabase(userId);

        status.ifPresent(value -> cache(userId, value));

        return status;
    }

    public void evict(UUID userId) {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException exception) {
            log.warn("Failed to evict user status cache: userId={}", userId, exception);
        }
    }

    private Optional<UserSecurityStatus> loadFromDatabase(UUID userId) {
        return userRepository.findById(userId)
                .map(user -> new UserSecurityStatus(
                        user.isEnabled(),
                        user.getTokenVersion()
                ));
    }

    private void cache(UUID userId, UserSecurityStatus status) {
        try {
            redisTemplate.opsForValue().set(
                    key(userId),
                    serialize(status),
                    properties.effectiveTtl()
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to cache user status: userId={}", userId, exception);
        }
    }

    private Optional<UserSecurityStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String[] parts = value.split(":");

        if (parts.length != 2) {
            return Optional.empty();
        }

        try {
            boolean enabled = Boolean.parseBoolean(parts[0]);
            long tokenVersion = Long.parseLong(parts[1]);

            return Optional.of(new UserSecurityStatus(enabled, tokenVersion));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String serialize(UserSecurityStatus status) {
        return status.enabled() + ":" + status.tokenVersion();
    }

    private String key(UUID userId) {
        return KEY_PREFIX + userId;
    }
}