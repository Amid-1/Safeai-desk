package ru.safeai.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import ru.safeai.gateway.common.exception.RateLimitExceededException;
import ru.safeai.gateway.common.exception.RateLimitUnavailableException;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AiMessageRateLimitProperties.class)
public class RedisRateLimitService {

    private static final String AI_MESSAGE_LIMIT_TYPE = "AI_MESSAGE";
    private static final String AI_MESSAGE_WINDOW = "1h";

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final AiMessageRateLimitProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final RateLimitKeyFactory keyFactory;

    public void checkAiMessageAllowed(SafeAiUserPrincipal user) {
        Objects.requireNonNull(user, "user не должен быть null");

        if (!properties.isEnabled()) {
            return;
        }

        int limit = isAdminOrSuperAdmin(user)
                ? properties.effectiveAdminLimitPerHour()
                : properties.effectiveUserLimitPerHour();

        Duration window = Duration.ofHours(1);

        String key = keyFactory.aiMessageUser(user.getId());

        try {
            RateLimitResult result = rateLimiter.incrementAndGet(key, window);
            long count = result.count();

            if (count > limit) {
                if (count == limit + 1) {
                    Map<String, Object> details = Map.of(
                            "type", AI_MESSAGE_LIMIT_TYPE,
                            "limit", limit,
                            "window", AI_MESSAGE_WINDOW,
                            "count", count
                    );

                    eventPublisher.publishEvent(new RateLimitExceededEvent(
                            user.getId(),
                            user.getOrganizationId(),
                            AI_MESSAGE_LIMIT_TYPE,
                            limit,
                            AI_MESSAGE_WINDOW,
                            details
                    ));
                }

                throw new RateLimitExceededException(
                        "Превышен лимит AI-запросов. Лимит: " + limit + " в час",
                        Duration.ofSeconds(result.ttlSeconds())
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

    private boolean isAdminOrSuperAdmin(SafeAiUserPrincipal user) {
        return user.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority ->
                        "ROLE_ADMIN".equals(authority)
                                || "ROLE_SUPER_ADMIN".equals(authority)
                );
    }
}