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
@EnableConfigurationProperties(
        AiMessageRateLimitProperties.class
)
public class RedisRateLimitService {

    private static final String AI_MESSAGE_USER_LIMIT_TYPE =
            "AI_MESSAGE_USER";

    private static final String
            AI_MESSAGE_ORGANIZATION_LIMIT_TYPE =
            "AI_MESSAGE_ORGANIZATION";

    private static final String AI_MESSAGE_BOTH_LIMIT_TYPE =
            "AI_MESSAGE_USER_AND_ORGANIZATION";

    private static final String AI_MESSAGE_WINDOW = "1h";

    private static final Duration WINDOW =
            Duration.ofHours(1);

    private final RedisFixedWindowRateLimiter rateLimiter;
    private final AiMessageRateLimitProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final RateLimitKeyFactory keyFactory;

    /**
     * Fail-closed policy:
     * a Redis failure blocks paid AI traffic with 503.
     * <p>
     * User and organization counters are checked atomically. Counters are
     * incremented only if both dimensions allow the request.
     */
    public void checkAiMessageAllowed(
            SafeAiUserPrincipal user
    ) {
        Objects.requireNonNull(
                user,
                "user не должен быть null"
        );

        if (!properties.isEnabled()) {
            return;
        }

        int userLimit = isAdminOrSuperAdmin(user)
                ? properties.effectiveAdminLimitPerHour()
                : properties.effectiveUserLimitPerHour();

        int organizationLimit =
                properties
                        .effectiveOrganizationLimitPerHour();

        DualRateLimitResult result;

        try {
            result = rateLimiter.tryIncrementBoth(
                    keyFactory.aiMessageUser(
                            user.getId()
                    ),
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

        enforceDecision(
                user,
                userLimit,
                organizationLimit,
                result
        );
    }

    /**
     * Record pattern intentionally avoids coupling this service to the
     * component accessor names of DualRateLimitResult. Its component order is:
     * decision, user count, organization count, user TTL, organization TTL,
     * notification marker.
     */
    private void enforceDecision(
            SafeAiUserPrincipal user,
            int userLimit,
            int organizationLimit,
            DualRateLimitResult result
    ) {
        Objects.requireNonNull(
                result,
                "rateLimiter вернул null"
        );

        RateLimitDecision decision =
                Objects.requireNonNull(
                        result.decision(),
                        "rateLimiter вернул null decision"
                );

        if (result.allowed()) {
            return;
        }

        long userCount =
                result.firstCount();

        long organizationCount =
                result.secondCount();

        long userTtlSeconds =
                result.firstTtlSeconds();

        long organizationTtlSeconds =
                result.secondTtlSeconds();

        ExceededLimit exceeded =
                resolveExceededLimit(
                        decision,
                        userLimit,
                        organizationLimit,
                        userTtlSeconds,
                        organizationTtlSeconds
                );

        if (result.exceededNotification()) {
            publishExceededEventBestEffort(
                    user,
                    exceeded,
                    decision,
                    userLimit,
                    organizationLimit,
                    userCount,
                    organizationCount,
                    userTtlSeconds,
                    organizationTtlSeconds
            );
        }

        throw new RateLimitExceededException(
                exceeded.message(),
                Duration.ofSeconds(
                        result.retryAfterSeconds()
                )
        );
    }

    private ExceededLimit resolveExceededLimit(
            RateLimitDecision decision,
            int userLimit,
            int organizationLimit,
            long userTtlSeconds,
            long organizationTtlSeconds
    ) {
        return switch (decision) {
            case FIRST_EXCEEDED -> new ExceededLimit(
                    AI_MESSAGE_USER_LIMIT_TYPE,
                    userLimit,
                    positiveTtl(userTtlSeconds),
                    "Превышен лимит AI-запросов "
                            + "пользователя. Лимит: "
                            + userLimit
                            + " в час"
            );

            case SECOND_EXCEEDED -> new ExceededLimit(
                    AI_MESSAGE_ORGANIZATION_LIMIT_TYPE,
                    organizationLimit,
                    positiveTtl(
                            organizationTtlSeconds
                    ),
                    "Превышен лимит AI-запросов "
                            + "организации. Лимит: "
                            + organizationLimit
                            + " в час"
            );

            case BOTH_EXCEEDED -> new ExceededLimit(
                    AI_MESSAGE_BOTH_LIMIT_TYPE,
                    userLimit,
                    Math.max(
                            positiveTtl(
                                    userTtlSeconds
                            ),
                            positiveTtl(
                                    organizationTtlSeconds
                            )
                    ),
                    "Превышены лимиты AI-запросов "
                            + "пользователя и организации"
            );

            case ALLOWED -> throw new IllegalArgumentException(
                    "ALLOWED не является превышением лимита"
            );
        };
    }

    private void publishExceededEventBestEffort(
            SafeAiUserPrincipal user,
            ExceededLimit exceeded,
            RateLimitDecision decision,
            int userLimit,
            int organizationLimit,
            long userCount,
            long organizationCount,
            long userTtlSeconds,
            long organizationTtlSeconds
    ) {
        Map<String, Object> details = Map.ofEntries(
                Map.entry(
                        "type",
                        exceeded.type()
                ),
                Map.entry(
                        "limit",
                        exceeded.limit()
                ),
                Map.entry(
                        "window",
                        AI_MESSAGE_WINDOW
                ),
                Map.entry(
                        "userLimit",
                        userLimit
                ),
                Map.entry(
                        "organizationLimit",
                        organizationLimit
                ),
                Map.entry(
                        "userCount",
                        userCount
                ),
                Map.entry(
                        "organizationCount",
                        organizationCount
                ),
                Map.entry(
                        "userTtlSeconds",
                        positiveTtl(userTtlSeconds)
                ),
                Map.entry(
                        "organizationTtlSeconds",
                        positiveTtl(
                                organizationTtlSeconds
                        )
                ),
                Map.entry(
                        "retryAfterSeconds",
                        exceeded.retryAfterSeconds()
                ),
                Map.entry(
                        "decision",
                        decision.name()
                )
        );

        try {
            eventPublisher.publishEvent(
                    new RateLimitExceededEvent(
                            user.getId(),
                            user.getOrganizationId(),
                            user.getEmail(),
                            null,
                            user.getOrganizationId(),
                            exceeded.type(),
                            exceeded.limit(),
                            AI_MESSAGE_WINDOW,
                            details
                    )
            );
        } catch (RuntimeException exception) {
            /*
             * Audit/event publication is best effort. A publication failure
             * must not replace the intended HTTP 429 with HTTP 503.
             */
            log.warn(
                    "Failed to publish rate-limit exceeded event: "
                            + "userId={}, organizationId={}, type={}",
                    user.getId(),
                    user.getOrganizationId(),
                    exceeded.type(),
                    exception
            );
        }
    }

    private long positiveTtl(long ttlSeconds) {
        return Math.max(1L, ttlSeconds);
    }

    private boolean isAdminOrSuperAdmin(
            SafeAiUserPrincipal user
    ) {
        return user.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority ->
                        "ROLE_ADMIN".equals(authority)
                                || "ROLE_SUPER_ADMIN"
                                .equals(authority)
                );
    }

    private record ExceededLimit(
            String type,
            int limit,
            long retryAfterSeconds,
            String message
    ) {
        private ExceededLimit {
            Objects.requireNonNull(
                    type,
                    "type не должен быть null"
            );
            Objects.requireNonNull(
                    message,
                    "message не должен быть null"
            );

            if (limit <= 0) {
                throw new IllegalArgumentException(
                        "limit должен быть положительным"
                );
            }

            if (retryAfterSeconds <= 0) {
                throw new IllegalArgumentException(
                        "retryAfterSeconds должен быть положительным"
                );
            }
        }
    }
}