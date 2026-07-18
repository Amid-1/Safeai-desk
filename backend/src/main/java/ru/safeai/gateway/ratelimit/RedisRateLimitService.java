package ru.safeai.gateway.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(AiMessageRateLimitProperties.class)
public class RedisRateLimitService {

    private static final String AI_MESSAGE_USER_LIMIT_TYPE =
            "AI_MESSAGE_USER";

    private static final String AI_MESSAGE_ORGANIZATION_LIMIT_TYPE =
            "AI_MESSAGE_ORGANIZATION";

    private static final String AI_MESSAGE_BOTH_LIMIT_TYPE =
            "AI_MESSAGE_USER_AND_ORGANIZATION";

    private static final String AI_MESSAGE_WINDOW = "1h";
    private static final Duration WINDOW = Duration.ofHours(1);

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final AiMessageRateLimitProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final RateLimitKeyFactory keyFactory;

    /**
     * Fail-closed strategy.

     * User and organization counters are checked and increased
     * atomically. Neither counter is spent when either dimension
     * has already exhausted its current fixed window.
     */
    public void checkAiMessageAllowed(
            SafeAiUserPrincipal user
    ) {
        Objects.requireNonNull(user, "user must not be null");

        if (!properties.isEnabled()) {
            return;
        }

        int userLimit = isAdminOrSuperAdmin(user)
                ? properties.effectiveAdminLimitPerHour()
                : properties.effectiveUserLimitPerHour();

        int organizationLimit =
                properties.effectiveOrganizationLimitPerHour();

        DualRateLimitResult result;

        try {
            result = rateLimiter.tryIncrementBoth(
                    keyFactory.aiMessageUser(user.getId()),
                    userLimit,
                    keyFactory.aiMessageOrganization(
                            user.getOrganizationId()
                    ),
                    organizationLimit,
                    WINDOW
            );
        } catch (RuntimeException exception) {
            throw new RateLimitUnavailableException(
                    "Redis AI message rate limit недоступен",
                    exception
            );
        }

        if (result.allowed()) {
            return;
        }

        String type = exceededType(result.decision());
        int reportedLimit = reportedLimit(
                result.decision(),
                userLimit,
                organizationLimit
        );

        if (result.exceededNotification()) {
            publishExceededEventBestEffort(
                    user,
                    type,
                    reportedLimit,
                    result
            );
        }

        throw new RateLimitExceededException(
                exceededMessage(
                        result.decision(),
                        userLimit,
                        organizationLimit
                ),
                Duration.ofSeconds(result.retryAfterSeconds())
        );
    }

    private void publishExceededEventBestEffort(
            SafeAiUserPrincipal user,
            String type,
            int limit,
            DualRateLimitResult result
    ) {
        try {
            eventPublisher.publishEvent(
                    new RateLimitExceededEvent(
                            user.getId(),
                            user.getOrganizationId(),
                            type,
                            limit,
                            AI_MESSAGE_WINDOW,
                            Map.of(
                                    "type", type,
                                    "limit", limit,
                                    "window", AI_MESSAGE_WINDOW,
                                    "userCount",
                                    result.firstCount(),
                                    "organizationCount",
                                    result.secondCount(),
                                    "decision",
                                    result.decision().name()
                            )
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "Failed to publish rate-limit exceeded event: "
                            + "userId={}, organizationId={}, type={}",
                    user.getId(),
                    user.getOrganizationId(),
                    type,
                    exception
            );
        }
    }

    private String exceededType(
            RateLimitDecision decision
    ) {
        return switch (decision) {
            case FIRST_EXCEEDED ->
                    AI_MESSAGE_USER_LIMIT_TYPE;
            case SECOND_EXCEEDED ->
                    AI_MESSAGE_ORGANIZATION_LIMIT_TYPE;
            case BOTH_EXCEEDED ->
                    AI_MESSAGE_BOTH_LIMIT_TYPE;
            case ALLOWED -> throw new IllegalArgumentException(
                    "ALLOWED decision has no exceeded type"
            );
        };
    }

    private int reportedLimit(
            RateLimitDecision decision,
            int userLimit,
            int organizationLimit
    ) {
        return switch (decision) {
            case FIRST_EXCEEDED -> userLimit;
            case SECOND_EXCEEDED -> organizationLimit;
            case BOTH_EXCEEDED ->
                    Math.min(userLimit, organizationLimit);
            case ALLOWED -> throw new IllegalArgumentException(
                    "ALLOWED decision has no exceeded limit"
            );
        };
    }

    private String exceededMessage(
            RateLimitDecision decision,
            int userLimit,
            int organizationLimit
    ) {
        return switch (decision) {
            case FIRST_EXCEEDED ->
                    "Превышен лимит AI-запросов пользователя. "
                            + "Лимит: "
                            + userLimit
                            + " за период 1 час";
            case SECOND_EXCEEDED ->
                    "Превышен лимит AI-запросов организации. "
                            + "Лимит: "
                            + organizationLimit
                            + " за период 1 час";
            case BOTH_EXCEEDED ->
                    "Превышены лимиты AI-запросов пользователя "
                            + "и организации";
            case ALLOWED -> throw new IllegalArgumentException(
                    "ALLOWED decision has no exceeded message"
            );
        };
    }

    private boolean isAdminOrSuperAdmin(
            SafeAiUserPrincipal user
    ) {
        return user.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority ->
                        "ROLE_ADMIN".equals(authority)
                                || "ROLE_SUPER_ADMIN".equals(
                                authority
                        )
                );
    }
}
