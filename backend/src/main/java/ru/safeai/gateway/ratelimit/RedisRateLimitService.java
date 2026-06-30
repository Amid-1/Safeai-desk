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

    private static final String AI_MESSAGE_USER_LIMIT_TYPE = "AI_MESSAGE_USER";
    private static final String AI_MESSAGE_ORGANIZATION_LIMIT_TYPE = "AI_MESSAGE_ORGANIZATION";
    private static final String AI_MESSAGE_WINDOW = "1h";

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final AiMessageRateLimitProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final RateLimitKeyFactory keyFactory;

    /**
     * Fail-closed strategy:
     * if Redis is unavailable, AI message sending is blocked.

     * Rationale:
     * this gateway controls paid external AI-provider usage.
     * Blocking requests during Redis outage is safer than allowing unlimited traffic.
     */
    public void checkAiMessageAllowed(SafeAiUserPrincipal user) {
        Objects.requireNonNull(user, "user must not be null");

        if (!properties.isEnabled()) {
            return;
        }

        Duration window = Duration.ofHours(1);

        int userLimit = isAdminOrSuperAdmin(user)
                ? properties.effectiveAdminLimitPerHour()
                : properties.effectiveUserLimitPerHour();

        checkLimit(
                keyFactory.aiMessageUser(user.getId()),
                userLimit,
                window,
                user,
                AI_MESSAGE_USER_LIMIT_TYPE,
                "Превышен лимит AI-запросов пользователя. Лимит: " + userLimit + " в час"
        );

        int organizationLimit = properties.effectiveOrganizationLimitPerHour();

        checkLimit(
                keyFactory.aiMessageOrganization(user.getOrganizationId()),
                organizationLimit,
                window,
                user,
                AI_MESSAGE_ORGANIZATION_LIMIT_TYPE,
                "Превышен лимит AI-запросов организации. Лимит: " + organizationLimit + " в час"
        );
    }

    private void checkLimit(
            String key,
            int limit,
            Duration window,
            SafeAiUserPrincipal user,
            String type,
            String message
    ) {
        try {
            RateLimitResult result = rateLimiter.incrementAndGet(key, window);
            long count = result.count();

            if (count > limit) {
                if (count == limit + 1L) {
                    publishExceededEvent(user, type, limit, count);
                }

                throw new RateLimitExceededException(
                        message,
                        Duration.ofSeconds(result.ttlSeconds())
                );
            }
        } catch (RateLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RateLimitUnavailableException(
                    "Redis AI message rate limit недоступен",
                    exception
            );
        }
    }

    private void publishExceededEvent(
            SafeAiUserPrincipal user,
            String type,
            int limit,
            long count
    ) {
        eventPublisher.publishEvent(new RateLimitExceededEvent(
                user.getId(),
                user.getOrganizationId(),
                type,
                limit,
                AI_MESSAGE_WINDOW,
                Map.of(
                        "type", type,
                        "limit", limit,
                        "window", AI_MESSAGE_WINDOW,
                        "count", count
                )
        ));
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