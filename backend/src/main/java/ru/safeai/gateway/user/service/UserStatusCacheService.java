package ru.safeai.gateway.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.user.repository.UserRepository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(UserStatusCacheProperties.class)
public class UserStatusCacheService {

    private static final int SERIALIZED_PARTS = 5;

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final UserStatusCacheProperties properties;

    public Optional<UserSecurityStatus> getStatus(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }

        /*
         * В production cache по умолчанию выключен. PostgreSQL остаётся
         * источником истины для каждого authenticated request.
         */
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
                    "User status cache unavailable, "
                            + "fallback to PostgreSQL: userId={}",
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
                .filter(Objects::nonNull)
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

    private Optional<UserSecurityStatus> loadFromDatabase(
            UUID userId
    ) {
        return userRepository.findByIdWithOrganization(userId)
                .map(user -> new UserSecurityStatus(
                        user.getOrganization().getId(),
                        user.isEnabled(),
                        user.getOrganization().isEnabled(),
                        user.getTokenVersion(),
                        user.getOrganization().getAuthVersion()
                ));
    }

    private void cache(
            UUID userId,
            UserSecurityStatus status
    ) {
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

        /*
         * Старый четырёхполевой формат намеренно отклоняется.
         * Он не содержит organizationAuthVersion.
         */
        if (parts.length != SERIALIZED_PARTS) {
            return Optional.empty();
        }

        try {
            UUID organizationId =
                    UUID.fromString(parts[0]);

            Optional<Boolean> userEnabled =
                    parseBooleanStrict(parts[1]);

            Optional<Boolean> organizationEnabled =
                    parseBooleanStrict(parts[2]);

            if (userEnabled.isEmpty()
                    || organizationEnabled.isEmpty()) {
                return Optional.empty();
            }

            long tokenVersion =
                    Long.parseLong(parts[3]);

            long organizationAuthVersion =
                    Long.parseLong(parts[4]);

            if (tokenVersion < 0
                    || organizationAuthVersion < 0) {
                return Optional.empty();
            }

            return Optional.of(
                    new UserSecurityStatus(
                            organizationId,
                            userEnabled.get(),
                            organizationEnabled.get(),
                            tokenVersion,
                            organizationAuthVersion
                    )
            );
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<Boolean> parseBooleanStrict(
            String value
    ) {
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
                + ":" + status.userEnabled()
                + ":" + status.organizationEnabled()
                + ":" + status.tokenVersion()
                + ":" + status.organizationAuthVersion();
    }

    private String key(UUID userId) {
        return properties.effectiveKeyPrefix() + userId;
    }
}
