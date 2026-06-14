package ru.safeai.gateway.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AiMessageRateLimitProperties.class)
public class RedisRateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final AiMessageRateLimitProperties properties;

    public void checkAiMessageAllowed(SafeAiUserPrincipal user) {
        if (!properties.enabled()) {
            return;
        }

        int limit = isAdmin(user)
                ? properties.adminLimitPerHour()
                : properties.userLimitPerHour();

        String key = "rate-limit:ai-message:user:" + user.getId();

        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofHours(1));
        }

        if (count != null && count > limit) {
            throw new RateLimitExceededException(
                    "Превышен лимит AI-сообщений. Лимит: " + limit + " в час"
            );
        }
    }

    private boolean isAdmin(SafeAiUserPrincipal user) {
        return user.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }
}