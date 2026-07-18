package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(UserStatusCacheProperties.class)
public class UserStatusCacheService {

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final UserStatusCacheProperties properties;

    public Optional<UserSecurityStatus> getStatus(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }

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

            if (cached != null && !cached.isBlank()) {
                log.warn(
                        "Invalid user status cache value ignored: "
                                + "userId={}, key={}",
                        userId,
                        key
                );
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "User status cache unavailable, fallback to PostgreSQL: "
                            + "userId={}",
                    userId,
                    exception
            );
        }

        Optional<UserSecurityStatus> status =
                loadFromDatabase(userId);

        status.ifPresent(value -> cache(userId, value));

        return status;
    }

    public void evict(UUID userId) {
        if (userId == null || !properties.isEnabled()) {
            return;
        }

        try {
            redisTemplate.delete(key(userId));
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to evict user status cache: userId={}",
                    userId,
                    exception
            );
        }
    }

    public void evictAll(Collection<UUID> userIds) {
        if (userIds == null
                || userIds.isEmpty()
                || !properties.isEnabled()) {
            return;
        }

        List<String> keys = userIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(this::key)
                .toList();

        if (keys.isEmpty()) {
            return;
        }

        try {
            redisTemplate.delete(keys);
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to bulk-evict user status cache: count={}",
                    keys.size(),
                    exception
            );
        }
    }

    private Optional<UserSecurityStatus> loadFromDatabase(UUID userId) {
        return userRepository.findByIdWithOrganization(userId)
                .map(user -> new UserSecurityStatus(
                        user.getOrganization().getId(),
                        user.isEnabled(),
                        user.getOrganization().isEnabled(),
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
            log.warn(
                    "Failed to cache user status: userId={}",
                    userId,
                    exception
            );
        }
    }

    private Optional<UserSecurityStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        String[] parts = value.split(":", -1);

        if (parts.length != 4) {
            return Optional.empty();
        }

        UUID organizationId;

        try {
            organizationId = UUID.fromString(parts[0]);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }

        Optional<Boolean> userEnabled =
                parseBooleanStrict(parts[1]);

        Optional<Boolean> organizationEnabled =
                parseBooleanStrict(parts[2]);

        if (userEnabled.isEmpty()
                || organizationEnabled.isEmpty()) {
            return Optional.empty();
        }

        try {
            long tokenVersion = Long.parseLong(parts[3]);

            if (tokenVersion < 0) {
                return Optional.empty();
            }

            return Optional.of(new UserSecurityStatus(
                    organizationId,
                    userEnabled.get(),
                    organizationEnabled.get(),
                    tokenVersion
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> parseBooleanStrict(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Optional.of(true);
        }

        if ("false".equalsIgnoreCase(value)) {
            return Optional.of(false);
        }

        return Optional.empty();
    }

    private String serialize(UserSecurityStatus status) {
        return status.organizationId()
                + ":"
                + status.userEnabled()
                + ":"
                + status.organizationEnabled()
                + ":"
                + status.tokenVersion();
    }

    private String key(UUID userId) {
        return properties.effectiveKeyPrefix() + userId;
    }
}
