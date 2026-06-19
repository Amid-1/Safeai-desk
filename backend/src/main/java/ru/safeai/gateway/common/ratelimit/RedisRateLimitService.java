package ru.safeai.gateway.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AiMessageRateLimitProperties.class)
public class RedisRateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final AiMessageRateLimitProperties properties;
    private final AuditEventService auditEventService;

    public void checkAiMessageAllowed(SafeAiUserPrincipal user) {
        if (!properties.enabled()) {
            return;
        }

        int limit = isAdmin(user)
                ? properties.adminLimitPerHour()
                : properties.userLimitPerHour();

        String key = "rate-limit:ai-message:user:" + user.getId();

        try {
            Long count = redisTemplate.opsForValue().increment(key);

            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofHours(1));
            }

            if (count != null && count > limit) {
                auditEventService.record(
                        user.getId(),
                        AuditEventType.RATE_LIMIT_EXCEEDED,
                        Map.of(
                                "type", "AI_MESSAGE",
                                "limit", limit,
                                "window", "1h"
                        )
                );

                throw new RateLimitExceededException(
                        "Превышен лимит AI-сообщений. Лимит: " + limit + " в час"
                );
            }
        } catch (RateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RateLimitUnavailableException(
                    "Redis rate limit недоступен",
                    exception
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